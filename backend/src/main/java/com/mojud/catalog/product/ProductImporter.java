package com.mojud.catalog.product;

import com.mojud.catalog.CatalogImport;
import com.mojud.catalog.CatalogLimits;
import com.mojud.catalog.category.CategoryPaths;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.tenant.TenantScope;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link CatalogImport}: imported rows become products and variants, changing only what differs. */
@Component
class ProductImporter implements CatalogImport {

    private final ProductRepository products;
    private final ProductOptionRepository options;
    private final ProductVariantRepository variants;
    private final CategoryPaths categories;
    private final AdvisoryLocks locks;
    private final VariantQuota quota;
    private final Clock clock;

    ProductImporter(ProductRepository products, ProductOptionRepository options, ProductVariantRepository variants,
            CategoryPaths categories, AdvisoryLocks locks, VariantQuota quota, Clock clock) {
        this.products = products;
        this.options = options;
        this.variants = variants;
        this.categories = categories;
        this.locks = locks;
        this.quota = quota;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Result> apply(ProductRows rows) {
        // Category first: it takes the categories lock, which no SKU-lock holder ever waits for.
        UUID categoryId = rows.category() == null ? null : categories.resolve(rows.category());
        locks.lockForTransaction("skus:" + TenantScope.current().orElseThrow());
        Instant now = clock.instant();
        // New variants count against the plan's limit, like products made in the app.
        return quota.guarded(() -> {
            Product existing = find(rows);
            return existing == null ? create(rows, categoryId, now) : update(existing, rows, categoryId, now);
        }, () -> {
            products.flush();
            variants.flush();
        });
    }

    /** The product owning any of the SKUs, else the one on sale with this name, else none. */
    private Product find(ProductRows rows) {
        Set<String> skus = rows.variants().stream().map(VariantRow::sku).filter(Objects::nonNull)
                .map(sku -> sku.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<UUID> owners = skus.isEmpty() ? Set.of()
                : variants.findBySkus(skus).stream().map(ProductVariant::getProductId).collect(Collectors.toSet());
        if (owners.size() > 1) {
            throw new Rejected("skus_in_other_products");
        }
        if (owners.size() == 1) {
            return products.findById(owners.iterator().next()).orElseThrow();
        }
        List<Product> named = products.findActiveByName(rows.name());
        if (named.size() > 1) {
            throw new Rejected("product_name_ambiguous");
        }
        return named.isEmpty() ? null : named.get(0);
    }

    private List<Result> create(ProductRows rows, UUID categoryId, Instant now) {
        Product product = products.save(new Product(rows.name(), description(rows.description()), categoryId, now));
        Map<String, List<String>> values = valuesInOrder(rows.optionNames(), rows.variants());
        checkGrid(values, rows.variants().size());
        int position = 0;
        for (Map.Entry<String, List<String>> option : values.entrySet()) {
            options.save(new ProductOption(product.getId(), option.getKey(), option.getValue(), position++));
        }
        List<String> skus = skus(product.getName(), values, rows.variants());
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < rows.variants().size(); i++) {
            VariantRow row = rows.variants().get(i);
            ProductVariant variant = new ProductVariant(product.getId(), skus.get(i), ordered(row.options(), rows.optionNames()),
                    row.price(), row.costPrice(), i, now);
            if (row.lowStockThreshold() != null) {
                variant.changeLowStockThreshold(row.lowStockThreshold(), now);
            }
            results.add(new Result(variants.save(variant).getId(), Outcome.CREATED));
        }
        return results;
    }

    private List<Result> update(Product product, ProductRows rows, UUID categoryId, Instant now) {
        boolean productChanged = false;
        if (!product.getName().equals(rows.name())) {
            product.rename(rows.name(), now);
            productChanged = true;
        }
        String description = rows.description() == null ? null : description(rows.description());
        if (description != null && !description.equals(product.getDescription())) {
            product.describe(description, now);
            productChanged = true;
        }
        if (categoryId != null && !categoryId.equals(product.getCategoryId())) {
            product.categorise(categoryId, now);
            productChanged = true;
        }
        List<ProductOption> productOptions = options.findByProductIdOrderByPosition(product.getId());
        Map<String, String> optionNames = optionNamesOf(productOptions, rows.optionNames());
        List<ProductVariant> current = new ArrayList<>(variants.findByProductIdOrderByPosition(product.getId()));
        List<VariantRow> added = new ArrayList<>();
        List<Integer> addedAt = new ArrayList<>();
        List<Result> results = new ArrayList<>(rows.variants().size());
        for (int i = 0; i < rows.variants().size(); i++) {
            VariantRow row = rows.variants().get(i);
            Map<String, String> attributes = new LinkedHashMap<>();
            row.options().forEach((name, value) -> attributes.put(optionNames.get(name.toLowerCase(Locale.ROOT)), value));
            ProductVariant match = match(current, row.sku(), attributes);
            if (match == null) {
                added.add(new VariantRow(row.sku(), attributes, row.price(), row.costPrice(), row.lowStockThreshold()));
                addedAt.add(i);
                results.add(null);
                continue;
            }
            boolean changed = productChanged;
            if (match.getPrice() != row.price() || !Objects.equals(match.getCostPrice(), row.costPrice())) {
                match.changePrices(row.price(), row.costPrice(), now);
                changed = true;
            }
            if (row.lowStockThreshold() != null && !row.lowStockThreshold().equals(match.getLowStockThreshold())) {
                match.changeLowStockThreshold(row.lowStockThreshold(), now);
                changed = true;
            }
            if (row.sku() != null && !row.sku().equals(match.getSku())) {
                match.changeSku(row.sku(), now);
                changed = true;
            }
            results.add(new Result(match.getId(), changed ? Outcome.UPDATED : Outcome.UNCHANGED));
        }
        if (!added.isEmpty()) {
            addVariants(product, productOptions, current, added, addedAt, results, now);
        }
        return results;
    }

    /** New variants of an existing product: their new option values join the options. */
    private void addVariants(Product product, List<ProductOption> productOptions, List<ProductVariant> current,
            List<VariantRow> added, List<Integer> addedAt, List<Result> results, Instant now) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        productOptions.forEach(option -> values.put(option.getName(), new ArrayList<>(option.getValues())));
        for (VariantRow variant : added) {
            variant.options().forEach((name, value) -> {
                List<String> known = values.get(name);
                if (known.stream().noneMatch(v -> v.equalsIgnoreCase(value))) {
                    known.add(value);
                }
            });
        }
        checkGrid(values, current.size() + added.size());
        for (ProductOption option : productOptions) {
            option.change(option.getName(), values.get(option.getName()), option.getPosition());
        }
        List<String> skus = skus(product.getName(), values, added);
        int position = current.stream().mapToInt(ProductVariant::getPosition).max().orElse(-1) + 1;
        for (int i = 0; i < added.size(); i++) {
            VariantRow row = added.get(i);
            ProductVariant variant = new ProductVariant(product.getId(), skus.get(i), row.options(), row.price(),
                    row.costPrice(), position++, now);
            if (row.lowStockThreshold() != null) {
                variant.changeLowStockThreshold(row.lowStockThreshold(), now);
            }
            variants.save(variant);
            current.add(variant);
            results.set(addedAt.get(i), new Result(variant.getId(), Outcome.CREATED));
        }
        product.touch(now);
    }

    /** By SKU first (ignoring case), then by option values (ignoring case). */
    private static ProductVariant match(List<ProductVariant> current, String sku, Map<String, String> attributes) {
        if (sku != null) {
            for (ProductVariant variant : current) {
                if (variant.getSku().equalsIgnoreCase(sku)) {
                    return variant;
                }
            }
        }
        for (ProductVariant variant : current) {
            if (sameValues(variant.getAttributes(), attributes)) {
                return variant;
            }
        }
        return null;
    }

    private static boolean sameValues(Map<String, String> a, Map<String, String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : a.entrySet()) {
            String other = b.get(entry.getKey());
            if (other == null || !other.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** The sheet's option names (lower-case) to the product's; the sets must agree, ignoring case. */
    private static Map<String, String> optionNamesOf(List<ProductOption> productOptions, List<String> sheetNames) {
        Map<String, String> byLower = new LinkedHashMap<>();
        productOptions.forEach(option -> byLower.put(option.getName().toLowerCase(Locale.ROOT), option.getName()));
        Set<String> sheet = sheetNames.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (!sheet.equals(byLower.keySet())) {
            throw new Rejected("options_differ", productOptions.isEmpty() ? "-"
                    : productOptions.stream().map(ProductOption::getName).collect(Collectors.joining(", ")));
        }
        return byLower;
    }

    /** Each option's values in the order they first appear. */
    private static Map<String, List<String>> valuesInOrder(List<String> optionNames, List<VariantRow> rows) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        optionNames.forEach(name -> values.put(name, new ArrayList<>()));
        for (VariantRow row : rows) {
            row.options().forEach((name, value) -> {
                List<String> known = values.get(name);
                if (known.stream().noneMatch(v -> v.equalsIgnoreCase(value))) {
                    known.add(value);
                }
            });
        }
        return values;
    }

    private static void checkGrid(Map<String, List<String>> values, int variantCount) {
        if (values.size() > CatalogLimits.MAX_OPTIONS) {
            throw new Rejected("too_many_options");
        }
        if (values.values().stream().anyMatch(list -> list.size() > CatalogLimits.MAX_VALUES)) {
            throw new Rejected("too_many_values");
        }
        if (variantCount > CatalogLimits.MAX_VARIANTS) {
            throw new Rejected("too_many_variants");
        }
    }

    /** Given SKUs as they are; the missing ones generated as the product form does. */
    private List<String> skus(String productName, Map<String, List<String>> values, List<VariantRow> rows) {
        String base = SkuGenerator.base(productName);
        Set<String> taken = new HashSet<>(variants.findSkusStartingWith(base));
        rows.stream().map(VariantRow::sku).filter(Objects::nonNull).forEach(sku -> taken.add(sku.toUpperCase(Locale.ROOT)));
        List<String> skus = new ArrayList<>();
        for (VariantRow row : rows) {
            skus.add(row.sku() != null ? row.sku()
                    : SkuGenerator.generate(base, List.of(row.options()), values, taken).get(0));
            taken.add(skus.get(skus.size() - 1).toUpperCase(Locale.ROOT));
        }
        return skus;
    }

    private static Map<String, String> ordered(Map<String, String> options, List<String> names) {
        Map<String, String> ordered = new LinkedHashMap<>();
        names.forEach(name -> ordered.put(name, options.get(name)));
        return ordered;
    }

    private static String description(String text) {
        return text == null ? "" : text.strip();
    }
}
