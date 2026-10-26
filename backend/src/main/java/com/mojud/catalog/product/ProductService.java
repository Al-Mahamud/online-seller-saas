package com.mojud.catalog.product;

import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.audit.Changes;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.money.Money;
import com.mojud.shared.paging.Cursor;
import com.mojud.shared.paging.CursorPage;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Products of the selected business (implementation-plan.md 3b).
 *
 * <p>Every product has at least one variant: a product without options has one plain variant, so
 * stock and orders always point at a variant. Variants are never deleted: when the options change,
 * combinations that leave the grid are archived and come back, with their SKU and prices, if they
 * return.
 *
 * <p>Writes that create or change SKUs take the business's SKU lock, so generated SKUs never clash
 * with each other; unique indexes still decide, and their violations become clear errors.
 */
@Service
class ProductService {

    static final int MAX_NAME = 200;
    static final int MAX_OPTION_TEXT = 30;
    static final int MAX_TAGS = 20;

    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");
    private static final UUID LAST_ID = new UUID(-1L, -1L);

    private static final Map<String, ErrorCode> CONSTRAINT_ERRORS = Map.of(
            // Another business's (or a deleted) category or tag looks like one that does not exist.
            "fk_products_category", ErrorCode.NOT_FOUND,
            "fk_product_tags_tag", ErrorCode.NOT_FOUND,
            "uq_product_variants_sku", ErrorCode.SKU_TAKEN);

    /** An option as given: {@code id} keeps an existing option (so it can be renamed). */
    record OptionInput(UUID id, String name, List<String> values) {
    }

    record CreateCommand(String name, String description, UUID categoryId, List<UUID> tagIds,
            List<OptionInput> options, long price, Long costPrice, String sku) {
    }

    /** Null fields stay as they are. */
    record UpdateCommand(String name, String description, UUID categoryId, boolean clearCategory, List<UUID> tagIds,
            Long version) {
    }

    /** Null fields stay as they are; {@code sku} blank means "generate one". */
    record VariantCommand(String sku, Long price, Long costPrice, boolean clearCostPrice, Integer lowStockThreshold,
            boolean useDefaultThreshold, Long version) {
    }

    private final ProductRepository products;
    private final ProductOptionRepository options;
    private final ProductVariantRepository variants;
    private final ProductTagRepository productTags;
    private final TransactionTemplate transactions;
    private final AdvisoryLocks locks;
    private final AuditTrail audit;
    private final Clock clock;

    ProductService(ProductRepository products, ProductOptionRepository options, ProductVariantRepository variants,
            ProductTagRepository productTags, TransactionTemplate transactions, AdvisoryLocks locks, AuditTrail audit,
            Clock clock) {
        this.products = products;
        this.options = options;
        this.variants = variants;
        this.productTags = productTags;
        this.transactions = transactions;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
    }

    // Reading.

    @Transactional(readOnly = true)
    CursorPage<ProductViews.Summary> list(ProductStatus status, UUID categoryId, UUID tagId, String cursor,
            Integer limit) {
        int pageSize = CursorPage.limit(limit);
        Cursor after = Cursor.decode(cursor);
        // The first page starts after everything: no null parameter, whose type PostgreSQL cannot tell.
        List<Product> page = products.findPage(status, categoryId, tagId,
                after == null ? END_OF_TIME : Instant.parse(after.sortKey()), after == null ? LAST_ID : after.id(),
                Limit.of(pageSize + 1));
        CursorPage<Product> paged = CursorPage.of(page, pageSize, p -> new Cursor(p.getCreatedAt().toString(), p.getId()));
        List<UUID> ids = paged.items().stream().map(Product::getId).toList();
        Map<UUID, List<ProductVariant>> variantsByProduct = variants.findByProductIdInOrderByPosition(ids).stream()
                .collect(Collectors.groupingBy(ProductVariant::getProductId));
        Map<UUID, List<ProductViews.Tag>> tags = tagsOf(ids);
        List<ProductViews.Summary> items = paged.items().stream().map(product -> {
            List<ProductVariant> active = variantsByProduct.getOrDefault(product.getId(), List.of()).stream()
                    .filter(ProductVariant::isActive).toList();
            Money min = active.stream().map(v -> Money.ofPoisha(v.getPrice())).min(Comparator.naturalOrder()).orElse(null);
            Money max = active.stream().map(v -> Money.ofPoisha(v.getPrice())).max(Comparator.naturalOrder()).orElse(null);
            return new ProductViews.Summary(product.getId(), product.getName(), product.getCategoryId(),
                    product.getStatus(), tags.getOrDefault(product.getId(), List.of()), active.size(), min, max,
                    product.getCreatedAt());
        }).toList();
        return new CursorPage<>(items, paged.nextCursor());
    }

    @Transactional(readOnly = true)
    ProductViews.Detail get(UUID productId) {
        return detail(find(productId));
    }

    // Creating and editing products.

    ProductViews.Detail create(CreateCommand command) {
        String name = Text.cleanName(command.name(), "name", MAX_NAME);
        String description = description(command.description());
        List<VariantGrid.Option> grid = cleanOptions(command.options());
        Set<UUID> tagIds = cleanTags(command.tagIds());
        String requestedSku = command.sku() == null || command.sku().isBlank() ? null : cleanSku(command.sku());
        if (requestedSku != null && !grid.isEmpty()) {
            throw ApiException.invalidField("sku", "field.sku_invalid");
        }
        return writing(true, () -> {
            Instant now = clock.instant();
            Product product = products.save(new Product(name, description, command.categoryId(), now));
            for (int i = 0; i < grid.size(); i++) {
                options.save(new ProductOption(product.getId(), grid.get(i).name(), grid.get(i).values(), i));
            }
            List<Map<String, String>> combinations = VariantGrid.combinations(grid);
            List<String> skus = requestedSku != null ? List.of(requestedSku) : newSkus(product.getName(), grid, combinations);
            for (int i = 0; i < combinations.size(); i++) {
                variants.save(new ProductVariant(product.getId(), skus.get(i), combinations.get(i), command.price(),
                        command.costPrice(), i, now));
            }
            tagIds.forEach(tagId -> productTags.save(new ProductTag(product.getId(), tagId)));
            products.flush();
            return detail(product);
        });
    }

    ProductViews.Detail update(UUID productId, UpdateCommand command) {
        String name = command.name() == null ? null : Text.cleanName(command.name(), "name", MAX_NAME);
        String description = command.description() == null ? null : description(command.description());
        Set<UUID> tagIds = command.tagIds() == null ? null : cleanTags(command.tagIds());
        return writing(false, () -> {
            Product product = find(productId);
            requireVersion(product.getVersion(), command.version());
            Instant now = clock.instant();
            if (name != null) {
                product.rename(name, now);
            }
            if (description != null) {
                product.describe(description, now);
            }
            if (command.clearCategory()) {
                product.categorise(null, now);
            } else if (command.categoryId() != null) {
                product.categorise(command.categoryId(), now);
            }
            if (tagIds != null) {
                productTags.deleteByProduct(productId);
                productTags.flush();
                tagIds.forEach(tagId -> productTags.save(new ProductTag(productId, tagId)));
                product.touch(now);
            }
            products.saveAndFlush(product);
            return detail(product);
        });
    }

    /** Archived products leave the default list and cannot be ordered (7a); nothing is deleted. */
    ProductViews.Detail changeStatus(UUID productId, ProductStatus status) {
        return writing(false, () -> {
            Product product = find(productId);
            product.changeStatus(status, clock.instant());
            products.saveAndFlush(product);
            return detail(product);
        });
    }

    // Options and the variant grid.

    /**
     * Replaces the product's options and rebuilds its variant grid:
     * <ul>
     * <li>an option given with its {@code id}, or with the same name, keeps its variants (renaming it
     * renames their attribute);
     * <li>a new option gives the existing variants its first value, so they stay (with SKU, prices
     * and stock) as that value: adding Color: Red, Blue to S and M keeps S and M as S/Red and M/Red;
     * <li>combinations that stay keep their variant (SKU, prices); archived ones come back;
     * <li>new combinations get new variants priced {@code price}/{@code costPrice}, or like the
     * first active variant when not given;
     * <li>combinations that leave are archived. Removing an option keeps one variant per remaining
     * combination and archives the others.
     * </ul>
     */
    ProductViews.Detail replaceOptions(UUID productId, List<OptionInput> inputs, Long price, Long costPrice,
            Long version) {
        List<VariantGrid.Option> grid = cleanOptions(inputs);
        return writing(true, () -> {
            Product product = find(productId);
            requireVersion(product.getVersion(), version);
            Instant now = clock.instant();
            List<ProductOption> existing = options.findByProductIdOrderByPosition(productId);

            // Which existing option each input continues: by id, else by name.
            Map<UUID, ProductOption> kept = new LinkedHashMap<>();
            Map<String, String> renames = new HashMap<>();
            Map<String, String> added = new LinkedHashMap<>();
            for (int i = 0; i < inputs.size(); i++) {
                ProductOption match = continued(existing, inputs.get(i));
                if (match != null) {
                    kept.put(match.getId(), match);
                    renames.put(match.getName(), grid.get(i).name());
                } else {
                    added.put(grid.get(i).name(), grid.get(i).values().get(0));
                }
            }
            Set<String> removed = new HashSet<>();
            existing.stream().filter(option -> !kept.containsKey(option.getId()))
                    .forEach(option -> removed.add(option.getName()));

            // Each variant's attributes after renames and removed options.
            List<ProductVariant> all = variants.findByProductIdOrderByPosition(productId);
            Map<ProductVariant, Map<String, String>> reduced = new LinkedHashMap<>();
            for (ProductVariant variant : all) {
                Map<String, String> attributes = new LinkedHashMap<>();
                variant.getAttributes().forEach((key, value) -> {
                    if (!removed.contains(key)) {
                        attributes.put(renames.getOrDefault(key, key), value);
                    }
                });
                added.forEach(attributes::putIfAbsent);
                reduced.put(variant, attributes);
            }

            ProductVariant template = all.stream().filter(ProductVariant::isActive).findFirst()
                    .orElse(all.isEmpty() ? null : all.get(0));
            long newPrice = price != null ? price : template == null ? 0 : template.getPrice();
            Long newCost = costPrice != null ? costPrice : template == null ? null : template.getCostPrice();

            List<Map<String, String>> combinations = VariantGrid.combinations(grid);
            Set<ProductVariant> placed = new HashSet<>();
            List<Map<String, String>> missing = new ArrayList<>();
            List<Integer> missingPositions = new ArrayList<>();
            for (int position = 0; position < combinations.size(); position++) {
                Map<String, String> combination = combinations.get(position);
                Optional<ProductVariant> match = reduced.entrySet().stream()
                        .filter(entry -> !placed.contains(entry.getKey()) && entry.getValue().equals(combination))
                        .map(Map.Entry::getKey)
                        .min(Comparator.comparing((ProductVariant v) -> !v.isActive()).thenComparingInt(ProductVariant::getPosition));
                if (match.isPresent()) {
                    match.get().regrid(combination, ProductStatus.ACTIVE, position, now);
                    placed.add(match.get());
                } else {
                    missing.add(combination);
                    missingPositions.add(position);
                }
            }

            // Variants that left the grid: archived, after the grid, keeping attributes that cannot clash.
            Set<Map<String, String>> taken = new HashSet<>(combinations);
            int archivedPosition = combinations.size();
            for (ProductVariant variant : all) {
                if (!placed.contains(variant)) {
                    Map<String, String> attributes = taken.add(reduced.get(variant)) ? reduced.get(variant) : variant.getAttributes();
                    taken.add(attributes);
                    variant.regrid(attributes, ProductStatus.ARCHIVED, archivedPosition++, now);
                }
            }

            List<String> skus = newSkus(product.getName(), grid, missing);
            for (int i = 0; i < missing.size(); i++) {
                variants.save(new ProductVariant(productId, skus.get(i), missing.get(i), newPrice, newCost,
                        missingPositions.get(i), now));
            }

            // The option rows themselves.
            existing.stream().filter(option -> !kept.containsKey(option.getId())).forEach(options::delete);
            options.flush();
            for (int i = 0; i < inputs.size(); i++) {
                ProductOption match = continued(existing, inputs.get(i));
                if (match != null) {
                    match.change(grid.get(i).name(), grid.get(i).values(), i);
                } else {
                    options.save(new ProductOption(productId, grid.get(i).name(), grid.get(i).values(), i));
                }
            }
            product.touch(now);
            products.saveAndFlush(product);
            return detail(product);
        });
    }

    // Variants.

    ProductViews.Variant updateVariant(UUID variantId, VariantCommand command) {
        String requestedSku = command.sku() == null || command.sku().isBlank() ? null : cleanSku(command.sku());
        boolean generateSku = command.sku() != null && command.sku().isBlank();
        return writing(requestedSku != null || generateSku, () -> {
            ProductVariant variant = findVariant(variantId);
            requireVersion(variant.getVersion(), command.version());
            Instant now = clock.instant();
            if (requestedSku != null) {
                variant.changeSku(requestedSku, now);
            } else if (generateSku) {
                Product product = find(variant.getProductId());
                List<VariantGrid.Option> grid = options.findByProductIdOrderByPosition(product.getId()).stream()
                        .map(option -> new VariantGrid.Option(option.getName(), option.getValues())).toList();
                // The variant's own SKU does not block it: regenerating "WATCH" gives "WATCH" again.
                variant.changeSku(newSkus(product.getName(), grid, List.of(variant.getAttributes()),
                        variant.getSku()).get(0), now);
            }
            long price = command.price() != null ? command.price() : variant.getPrice();
            Long cost = command.clearCostPrice() ? null
                    : command.costPrice() != null ? command.costPrice() : variant.getCostPrice();
            Changes changes = new Changes()
                    .field("price", variant.getPrice(), price)
                    .field("costPrice", variant.getCostPrice(), cost);
            if (!changes.isEmpty()) {
                variant.changePrices(price, cost, now);
                CurrentBusiness business = CurrentBusiness.require();
                audit.record(business.businessId(), business.userId(), "variant.prices_changed", "variant", variantId,
                        "sku", variant.getSku(), "changes", changes.asMap());
            }
            if (command.useDefaultThreshold()) {
                variant.changeLowStockThreshold(null, now);
            } else if (command.lowStockThreshold() != null) {
                variant.changeLowStockThreshold(command.lowStockThreshold(), now);
            }
            variants.saveAndFlush(variant);
            return ProductViews.Variant.of(variant, showCost(), optionOrder(variant.getProductId()));
        });
    }

    /** A combination the seller does not make; stays out of orders until restored. */
    ProductViews.Variant changeVariantStatus(UUID variantId, ProductStatus status) {
        return writing(false, () -> {
            ProductVariant variant = findVariant(variantId);
            variant.changeStatus(status, clock.instant());
            variants.saveAndFlush(variant);
            return ProductViews.Variant.of(variant, showCost(), optionOrder(variant.getProductId()));
        });
    }

    // Helpers.

    /** One transaction; SKU-changing writes first take the business's SKU lock. */
    private <T> T writing(boolean changesSkus, Supplier<T> work) {
        return Constraints.translating(transactions, CONSTRAINT_ERRORS, () -> {
            if (changesSkus) {
                locks.lockForTransaction("skus:" + CurrentBusiness.require().businessId());
            }
            return work.get();
        });
    }

    private ProductViews.Detail detail(Product product) {
        List<ProductViews.Option> optionViews = options.findByProductIdOrderByPosition(product.getId()).stream()
                .map(option -> new ProductViews.Option(option.getId(), option.getName(), option.getValues())).toList();
        boolean showCost = showCost();
        List<String> optionOrder = optionViews.stream().map(ProductViews.Option::name).toList();
        List<ProductViews.Variant> variantViews = variants.findByProductIdOrderByPosition(product.getId()).stream()
                .map(variant -> ProductViews.Variant.of(variant, showCost, optionOrder)).toList();
        return new ProductViews.Detail(product.getId(), product.getName(), product.getDescription(),
                product.getCategoryId(), product.getStatus(), tagsOf(List.of(product.getId())).getOrDefault(product.getId(), List.of()),
                optionViews, variantViews, product.getCreatedAt(), product.getVersion() == null ? 0 : product.getVersion());
    }

    private Map<UUID, List<ProductViews.Tag>> tagsOf(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productTags.findTags(productIds).stream().collect(Collectors.groupingBy(TagRef::productId,
                LinkedHashMap::new, Collectors.mapping(tag -> new ProductViews.Tag(tag.id(), tag.name()), Collectors.toList())));
    }

    private List<String> newSkus(String productName, List<VariantGrid.Option> grid, List<Map<String, String>> combinations) {
        return newSkus(productName, grid, combinations, null);
    }

    /** {@code ownSku}: the SKU being replaced, which may be generated again. */
    private List<String> newSkus(String productName, List<VariantGrid.Option> grid, List<Map<String, String>> combinations,
            String ownSku) {
        if (combinations.isEmpty()) {
            return List.of();
        }
        String base = SkuGenerator.base(productName);
        Map<String, List<String>> positions = new LinkedHashMap<>();
        grid.forEach(option -> positions.put(option.name(), option.values()));
        Set<String> taken = new HashSet<>(variants.findSkusStartingWith(base));
        if (ownSku != null) {
            taken.remove(ownSku.toUpperCase(Locale.ROOT));
        }
        return SkuGenerator.generate(base, combinations, positions, taken);
    }

    /** Validated options: names and values tidied, no duplicates (ignoring case), grid not too big. */
    private static List<VariantGrid.Option> cleanOptions(List<OptionInput> inputs) {
        List<OptionInput> given = inputs == null ? List.of() : inputs;
        if (given.size() > VariantGrid.MAX_OPTIONS) {
            throw ApiException.invalidField("options", "field.too_many_options");
        }
        List<VariantGrid.Option> grid = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (OptionInput input : given) {
            String name = Text.cleanName(input.name(), "options", MAX_OPTION_TEXT);
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw ApiException.invalidField("options", "field.option_duplicate");
            }
            List<String> values = input.values() == null ? List.of() : input.values();
            if (values.isEmpty()) {
                throw ApiException.invalidField("options", "jakarta.validation.constraints.NotEmpty.message");
            }
            if (values.size() > VariantGrid.MAX_VALUES) {
                throw ApiException.invalidField("options", "field.too_many_values");
            }
            Set<String> seen = new LinkedHashSet<>();
            List<String> cleaned = new ArrayList<>();
            for (String value : values) {
                String clean = Text.cleanName(value, "options", MAX_OPTION_TEXT);
                if (!seen.add(clean.toLowerCase(Locale.ROOT))) {
                    throw ApiException.invalidField("options", "field.value_duplicate");
                }
                cleaned.add(clean);
            }
            grid.add(new VariantGrid.Option(name, List.copyOf(cleaned)));
        }
        if (VariantGrid.size(grid) > VariantGrid.MAX_VARIANTS) {
            throw ApiException.invalidField("options", "field.too_many_variants");
        }
        return grid;
    }

    private static ProductOption continued(List<ProductOption> existing, OptionInput input) {
        if (input.id() != null) {
            return existing.stream().filter(option -> option.getId().equals(input.id())).findFirst()
                    .orElseThrow(() -> ApiException.invalidField("options", "field.invalid_value"));
        }
        String name = input.name() == null ? "" : input.name().strip();
        return existing.stream().filter(option -> option.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private static Set<UUID> cleanTags(List<UUID> tagIds) {
        Set<UUID> tags = new LinkedHashSet<>(tagIds == null ? List.of() : tagIds);
        tags.remove(null);
        if (tags.size() > MAX_TAGS) {
            throw ApiException.invalidField("tagIds", "field.too_long");
        }
        return tags;
    }

    private static String cleanSku(String sku) {
        String cleaned = sku.strip();
        if (cleaned.length() > 64 || cleaned.chars().anyMatch(Character::isWhitespace)) {
            throw ApiException.invalidField("sku", "field.sku_invalid");
        }
        return cleaned;
    }

    private static String description(String text) {
        String cleaned = text == null ? "" : text.strip();
        if (cleaned.length() > 5000) {
            throw ApiException.invalidField("description", "field.too_long");
        }
        return cleaned;
    }

    /** A stale version from the client means someone else changed it since the client read it. */
    private static void requireVersion(Long actual, Long expected) {
        if (expected != null && !Objects.equals(actual, expected)) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    private List<String> optionOrder(UUID productId) {
        return options.findByProductIdOrderByPosition(productId).stream().map(ProductOption::getName).toList();
    }

    private Product find(UUID productId) {
        return products.findById(productId).orElseThrow(ApiException::notFound);
    }

    private ProductVariant findVariant(UUID variantId) {
        return variants.findById(variantId).orElseThrow(ApiException::notFound);
    }

    private static boolean showCost() {
        return CurrentBusiness.require().isAtLeast(BusinessRole.MANAGER);
    }
}
