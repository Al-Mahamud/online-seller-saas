package com.mojud.catalog.product;

import com.mojud.identity.UserDirectory;
import com.mojud.inventory.StockHistory;
import com.mojud.catalog.CatalogLimits;
import com.mojud.inventory.StockLevel;
import com.mojud.inventory.StockQueries;
import com.mojud.inventory.StockService;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.audit.Changes;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.files.FileLinks;
import com.mojud.shared.money.Money;
import com.mojud.shared.paging.Cursor;
import com.mojud.shared.paging.CursorPage;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.BusinessDefaults;
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

    static final int MAX_NAME = CatalogLimits.PRODUCT_NAME;
    static final int MAX_OPTION_TEXT = CatalogLimits.OPTION_TEXT;
    static final int MAX_TAGS = 20;
    /** The options behind a variant's name and size, under the names the rows store them. */
    static final String VARIANT_OPTION = "Variant";
    static final String SIZE_OPTION = "Size";
    static final int SEARCH_RESULTS = 50;

    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");
    private static final UUID LAST_ID = new UUID(-1L, -1L);

    private static final Map<String, ErrorCode> CONSTRAINT_ERRORS = Map.of(
            // Another business's (or a deleted) category or tag looks like one that does not exist.
            "fk_products_category", ErrorCode.NOT_FOUND,
            "fk_product_tags_tag", ErrorCode.NOT_FOUND,
            "uq_product_variants_sku", ErrorCode.SKU_TAKEN,
            // Last resort; replaceOptions keeps attributes unique, so this would be a race.
            "uq_product_variants_attributes", ErrorCode.CONCURRENT_MODIFICATION);

    /** An option as given: {@code id} keeps an existing option (so it can be renamed). */
    record OptionInput(UUID id, String name, List<String> values) {
    }

    /** {@code openingStock}: on hand for every new variant, recorded as an OPENING movement. */
    record CreateCommand(String name, String description, UUID categoryId, List<UUID> tagIds,
            List<OptionInput> options, long price, Long costPrice, String sku, int openingStock) {
    }

    /** Null fields stay as they are. */
    record UpdateCommand(String name, String description, UUID categoryId, boolean clearCategory, List<UUID> tagIds,
            Long version) {
    }

    /** A variant as the seller describes it; {@code name} and {@code size} may be blank. */
    record VariantRow(String name, String size, long price, Long costPrice, int quantity) {
    }

    record RowsCommand(String name, String description, UUID categoryId, List<UUID> tagIds, List<VariantRow> rows) {
    }

    /** Null fields stay as they are; {@code sku} blank means "generate one"; a blank name or size removes it. */
    record VariantCommand(String name, String size, String sku, Long price, Long costPrice, boolean clearCostPrice, Integer lowStockThreshold,
            boolean useDefaultThreshold, Long version) {
    }

    private final ProductRepository products;
    private final ProductOptionRepository options;
    private final ProductVariantRepository variants;
    private final ProductTagRepository productTags;
    private final ProductImageRepository images;
    private final StockService stock;
    private final StockQueries stockLevels;
    private final VariantQuota quota;
    private final BusinessDefaults defaults;
    private final UserDirectory users;
    private final TransactionTemplate transactions;
    private final AdvisoryLocks locks;
    private final AuditTrail audit;
    private final Clock clock;

    ProductService(ProductRepository products, ProductOptionRepository options, ProductVariantRepository variants,
            ProductTagRepository productTags, ProductImageRepository images, StockService stock,
            StockQueries stockLevels, UserDirectory users, TransactionTemplate transactions,
            AdvisoryLocks locks, AuditTrail audit, Clock clock, VariantQuota quota, BusinessDefaults defaults) {
        this.products = products;
        this.options = options;
        this.variants = variants;
        this.productTags = productTags;
        this.images = images;
        this.stock = stock;
        this.stockLevels = stockLevels;
        this.quota = quota;
        this.defaults = defaults;
        this.users = users;
        this.transactions = transactions;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
    }

    // Reading.

    /**
     * Newest first, page by page; or, with {@code q}, the best {@value #SEARCH_RESULTS} matches by
     * name or SKU (no further pages).
     */
    @Transactional(readOnly = true)
    CursorPage<ProductViews.Summary> list(ProductStatus status, UUID categoryId, UUID tagId, String q, String cursor,
            Integer limit) {
        if (q != null && !q.isBlank()) {
            String query = Text.cleanName(q, "q", 100);
            // A LIKE pattern for "contains", with LIKE's own characters (backslash, %, _) escaped.
            String pattern = "%" + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            return new CursorPage<>(summaries(products.search(CurrentBusiness.require().businessId(), status.name(),
                    categoryId, tagId, query, pattern, SEARCH_RESULTS)), null);
        }
        int pageSize = CursorPage.limit(limit);
        Cursor after = Cursor.decode(cursor);
        // The first page starts after everything: no null parameter, whose type PostgreSQL cannot tell.
        List<Product> page = products.findPage(status, categoryId, tagId,
                after == null ? END_OF_TIME : after.sortKeyAsInstant(), after == null ? LAST_ID : after.id(),
                Limit.of(pageSize + 1));
        CursorPage<Product> paged = CursorPage.of(page, pageSize, p -> new Cursor(p.getCreatedAt().toString(), p.getId()));
        return new CursorPage<>(summaries(paged.items()), paged.nextCursor());
    }

    private List<ProductViews.Summary> summaries(List<Product> page) {
        List<UUID> ids = page.stream().map(Product::getId).toList();
        Map<UUID, ProductImage> firstImage = new HashMap<>();
        images.findByProductIdInOrderByPosition(ids).forEach(image -> firstImage.putIfAbsent(image.getProductId(), image));
        List<ProductVariant> pageVariants = variants.findByProductIdInOrderByPosition(ids);
        Map<UUID, List<ProductVariant>> variantsByProduct = pageVariants.stream()
                .collect(Collectors.groupingBy(ProductVariant::getProductId));
        Map<UUID, StockLevel> levels = stockLevels.levels(pageVariants.stream().map(ProductVariant::getId).toList());
        Map<UUID, List<ProductViews.Tag>> tags = tagsOf(ids);
        int lowStockDefault = defaults.lowStockDefault();
        return page.stream().map(product -> {
            List<ProductVariant> active = variantsByProduct.getOrDefault(product.getId(), List.of()).stream()
                    .filter(ProductVariant::isActive).toList();
            Money min = active.stream().map(v -> Money.ofPoisha(v.getPrice())).min(Comparator.naturalOrder()).orElse(null);
            Money max = active.stream().map(v -> Money.ofPoisha(v.getPrice())).max(Comparator.naturalOrder()).orElse(null);
            int available = active.stream().mapToInt(v -> levels.get(v.getId()).available()).sum();
            boolean low = product.getStatus() == ProductStatus.ACTIVE && active.stream().anyMatch(v ->
                    levels.get(v.getId()).available() <= (v.getLowStockThreshold() != null ? v.getLowStockThreshold() : lowStockDefault));
            ProductImage image = firstImage.get(product.getId());
            return new ProductViews.Summary(product.getId(), product.getName(), product.getCategoryId(),
                    product.getStatus(), tags.getOrDefault(product.getId(), List.of()), active.size(), min, max,
                    available, low, image == null ? null : FileLinks.of(image.getFileId()).small(), product.getCreatedAt());
        }).toList();
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
        return growing(() -> {
            Instant now = clock.instant();
            Product product = products.save(new Product(name, description, command.categoryId(), now));
            for (int i = 0; i < grid.size(); i++) {
                options.save(new ProductOption(product.getId(), grid.get(i).name(), grid.get(i).values(), i));
            }
            List<Map<String, String>> combinations = VariantGrid.combinations(grid);
            List<String> skus = requestedSku != null ? List.of(requestedSku) : newSkus(product.getName(), grid, combinations);
            List<StockService.Line> opening = new ArrayList<>();
            for (int i = 0; i < combinations.size(); i++) {
                ProductVariant variant = variants.save(new ProductVariant(product.getId(), skus.get(i),
                        combinations.get(i), command.price(), command.costPrice(), i, now));
                if (command.openingStock() > 0) {
                    opening.add(new StockService.Line(variant.getId(), command.openingStock()));
                }
            }
            tagIds.forEach(tagId -> productTags.save(new ProductTag(product.getId(), tagId)));
            // The variants must exist before stock can point at them.
            products.flush();
            stock.opening(opening, StockService.Reference.none());
            return detail(product);
        });
    }

    /** One variant per row, each with its own prices and stock; not every combination has to exist. */
    ProductViews.Detail createWithRows(RowsCommand command) {
        String name = Text.cleanName(command.name(), "name", MAX_NAME);
        String description = description(command.description());
        Set<UUID> tagIds = cleanTags(command.tagIds());
        List<VariantRow> rows = command.rows();
        if (rows.isEmpty()) {
            throw ApiException.invalidField("variants", "jakarta.validation.constraints.NotEmpty.message");
        }
        if (rows.size() > VariantGrid.MAX_VARIANTS) {
            throw ApiException.invalidField("variants", "field.too_many_variants");
        }
        List<Map<String, String>> combinations = new ArrayList<>();
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (VariantRow row : rows) {
            Map<String, String> attributes = rowAttributes(row.name(), row.size(), rows.size() == 1, values);
            if (combinations.stream().anyMatch(attributes::equals)) {
                throw new ApiException(ErrorCode.VARIANT_DUPLICATE);
            }
            combinations.add(attributes);
        }
        List<VariantGrid.Option> grid = rowOptionNames(values).stream()
                .map(option -> new VariantGrid.Option(option, values.get(option))).toList();
        return growing(() -> {
            Instant now = clock.instant();
            Product product = products.save(new Product(name, description, command.categoryId(), now));
            for (int i = 0; i < grid.size(); i++) {
                options.save(new ProductOption(product.getId(), grid.get(i).name(), grid.get(i).values(), i));
            }
            List<String> skus = newSkus(product.getName(), grid, combinations);
            List<StockService.Line> opening = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                VariantRow row = rows.get(i);
                ProductVariant variant = variants.save(new ProductVariant(product.getId(), skus.get(i),
                        combinations.get(i), row.price(), row.costPrice(), i, now));
                if (row.quantity() > 0) {
                    opening.add(new StockService.Line(variant.getId(), row.quantity()));
                }
            }
            tagIds.forEach(tagId -> productTags.save(new ProductTag(product.getId(), tagId)));
            products.flush();
            stock.opening(opening, StockService.Reference.none());
            return detail(product);
        });
    }

    /** One more variant, with its own prices and stock; the others stay as they are. */
    ProductViews.Detail addVariant(UUID productId, VariantRow row, Long version) {
        return growing(() -> {
            Product product = find(productId);
            requireVersion(product.getVersion(), version);
            List<ProductVariant> existing = variants.findByProductIdOrderByPosition(productId);
            if (existing.size() >= VariantGrid.MAX_VARIANTS) {
                throw ApiException.invalidField("variants", "field.too_many_variants");
            }
            List<ProductOption> current = options.findByProductIdOrderByPosition(productId);
            Map<String, List<String>> values = new LinkedHashMap<>();
            current.forEach(option -> values.put(option.getName(), new ArrayList<>(option.getValues())));
            Map<String, String> attributes = rowAttributes(row.name(), row.size(), false, values);
            if (existing.stream().anyMatch(variant -> attributes.equals(variant.getAttributes()))) {
                throw new ApiException(ErrorCode.VARIANT_DUPLICATE);
            }
            saveOptionValues(productId, current, values);
            Instant now = clock.instant();
            List<VariantGrid.Option> grid = values.entrySet().stream()
                    .map(option -> new VariantGrid.Option(option.getKey(), option.getValue())).toList();
            int position = existing.stream().mapToInt(ProductVariant::getPosition).max().orElse(-1) + 1;
            ProductVariant variant = variants.save(new ProductVariant(productId,
                    newSkus(product.getName(), grid, List.of(attributes)).get(0), attributes, row.price(), row.costPrice(),
                    position, now));
            product.touch(now);
            products.saveAndFlush(product);
            if (row.quantity() > 0) {
                stock.opening(List.of(new StockService.Line(variant.getId(), row.quantity())),
                        StockService.Reference.none());
            }
            return detail(product);
        });
    }

    /**
     * A row's attributes. Each value joins {@code values} (option name to its values), spelt as that
     * option first had it, so "red" and "Red" are one colour. Both may be blank only for a product's
     * one plain variant ({@code plainAllowed}).
     */
    private static Map<String, String> rowAttributes(String name, String size, boolean plainAllowed,
            Map<String, List<String>> values) {
        Map<String, String> attributes = new LinkedHashMap<>();
        addAttribute(attributes, VARIANT_OPTION, name, values);
        addAttribute(attributes, SIZE_OPTION, size, values);
        if (attributes.isEmpty() && !plainAllowed) {
            throw ApiException.invalidField("variants", "field.variant_needs_name");
        }
        return attributes;
    }

    private static void addAttribute(Map<String, String> attributes, String option, String text,
            Map<String, List<String>> values) {
        if (text == null || text.isBlank()) {
            return;
        }
        String clean = Text.cleanName(text, "variants", MAX_OPTION_TEXT);
        List<String> known = values.computeIfAbsent(option, key -> new ArrayList<>());
        String spelt = known.stream().filter(clean::equalsIgnoreCase).findFirst().orElse(null);
        if (spelt == null) {
            if (known.size() >= VariantGrid.MAX_VALUES) {
                throw ApiException.invalidField("variants", "field.too_many_values");
            }
            known.add(clean);
            spelt = clean;
        }
        attributes.put(option, spelt);
    }

    /** The options in their order: a variant's name reads before its size, "Red / M", whichever came first. */
    private static List<String> rowOptionNames(Map<String, List<String>> values) {
        List<String> names = new ArrayList<>(values.keySet());
        int size = names.indexOf(SIZE_OPTION);
        if (size >= 0 && names.indexOf(VARIANT_OPTION) > size) {
            names.remove(VARIANT_OPTION);
            names.add(size, VARIANT_OPTION);
        }
        return names;
    }

    /** Stores what {@link #rowAttributes} added: new values on options, any new options, and their order. */
    private void saveOptionValues(UUID productId, List<ProductOption> current, Map<String, List<String>> values) {
        List<String> names = rowOptionNames(values);
        // An older product may already have other options.
        if (names.size() > VariantGrid.MAX_OPTIONS) {
            throw ApiException.invalidField("variants", "field.too_many_options");
        }
        for (int position = 0; position < names.size(); position++) {
            String name = names.get(position);
            Optional<ProductOption> saved = current.stream().filter(option -> option.getName().equals(name)).findFirst();
            if (saved.isPresent()) {
                saved.get().change(name, values.get(name), position);
            } else {
                options.save(new ProductOption(productId, name, values.get(name), position));
            }
        }
        options.flush();
    }

    /** A new name or size for a variant; null leaves that one as it is, blank removes it. */
    private void renameVariant(ProductVariant variant, String name, String size, Instant now) {
        UUID productId = variant.getProductId();
        List<ProductVariant> all = variants.findByProductIdOrderByPosition(productId);
        List<ProductOption> current = options.findByProductIdOrderByPosition(productId);
        Map<String, List<String>> values = new LinkedHashMap<>();
        current.forEach(option -> values.put(option.getName(), new ArrayList<>(option.getValues())));
        Map<String, String> attributes = new LinkedHashMap<>(variant.getAttributes());
        attributes.remove(VARIANT_OPTION);
        attributes.remove(SIZE_OPTION);
        Map<String, String> named = rowAttributes(
                name != null ? name : variant.getAttributes().get(VARIANT_OPTION),
                size != null ? size : variant.getAttributes().get(SIZE_OPTION),
                all.size() == 1 || !attributes.isEmpty(), values);
        attributes.putAll(named);
        if (attributes.equals(variant.getAttributes())) {
            return;
        }
        if (all.stream().anyMatch(other -> other != variant && attributes.equals(other.getAttributes()))) {
            throw new ApiException(ErrorCode.VARIANT_DUPLICATE);
        }
        saveOptionValues(productId, current, values);
        variant.regrid(attributes, variant.getStatus(), variant.getPosition(), now);
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
        return growing(() -> {
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
        return growing(() -> {
            Product product = find(productId);
            requireVersion(product.getVersion(), version);
            Instant now = clock.instant();
            List<ProductOption> existing = options.findByProductIdOrderByPosition(productId);
            // Decided once: which existing option each input continues (null: a new option).
            List<ProductOption> continues = matchOptions(existing, inputs, grid);

            Map<String, String> renames = new HashMap<>();
            Map<String, String> added = new LinkedHashMap<>();
            for (int i = 0; i < grid.size(); i++) {
                if (continues.get(i) != null) {
                    renames.put(continues.get(i).getName(), grid.get(i).name());
                } else {
                    added.put(grid.get(i).name(), grid.get(i).values().get(0));
                }
            }
            Set<String> removed = new HashSet<>();
            existing.stream().filter(option -> !continues.contains(option)).forEach(option -> removed.add(option.getName()));

            // Two views of each variant: "own" is its combination after renames and removed options;
            // "extended" also takes the first value of each new option.
            List<ProductVariant> all = variants.findByProductIdOrderByPosition(productId);
            Map<ProductVariant, Map<String, String>> own = new LinkedHashMap<>();
            Map<ProductVariant, Map<String, String>> extended = new LinkedHashMap<>();
            for (ProductVariant variant : all) {
                Map<String, String> attributes = new HashMap<>();
                variant.getAttributes().forEach((key, value) -> {
                    if (!removed.contains(key)) {
                        attributes.put(renames.getOrDefault(key, key), value);
                    }
                });
                own.put(variant, attributes);
                Map<String, String> withAdded = new HashMap<>(attributes);
                added.forEach(withAdded::putIfAbsent);
                extended.put(variant, withAdded);
            }

            ProductVariant template = all.stream().filter(ProductVariant::isActive).findFirst()
                    .orElse(all.isEmpty() ? null : all.get(0));
            long newPrice = price != null ? price : template == null ? 0 : template.getPrice();
            Long newCost = costPrice != null ? costPrice : template == null ? null : template.getCostPrice();

            // Each combination keeps the variant that already is it; only then one that becomes it by
            // taking a new option's first value. Among several, an active one, then the first.
            List<Map<String, String>> combinations = VariantGrid.combinations(grid);
            Map<Integer, ProductVariant> chosen = new HashMap<>();
            Set<ProductVariant> placed = new HashSet<>();
            for (Map<Map<String, String>, List<ProductVariant>> index : List.of(byAttributes(own), byAttributes(extended))) {
                for (int position = 0; position < combinations.size(); position++) {
                    if (chosen.containsKey(position)) {
                        continue;
                    }
                    for (ProductVariant candidate : index.getOrDefault(new HashMap<>(combinations.get(position)), List.of())) {
                        if (placed.add(candidate)) {
                            chosen.put(position, candidate);
                            break;
                        }
                    }
                }
            }
            List<Map<String, String>> missing = new ArrayList<>();
            List<Integer> missingPositions = new ArrayList<>();
            for (int position = 0; position < combinations.size(); position++) {
                if (chosen.containsKey(position)) {
                    chosen.get(position).regrid(combinations.get(position), ProductStatus.ACTIVE, position, now);
                } else {
                    missing.add(combinations.get(position));
                    missingPositions.add(position);
                }
            }

            // Variants that left the grid: archived after it, with attributes no other variant has.
            Set<Map<String, String>> taken = new HashSet<>();
            combinations.forEach(combination -> taken.add(new HashMap<>(combination)));
            int archivedPosition = combinations.size();
            for (ProductVariant variant : all) {
                if (!placed.contains(variant)) {
                    variant.regrid(freeAttributes(variant, own.get(variant), taken), ProductStatus.ARCHIVED,
                            archivedPosition++, now);
                }
            }

            List<String> skus = newSkus(product.getName(), grid, missing);
            for (int i = 0; i < missing.size(); i++) {
                variants.save(new ProductVariant(productId, skus.get(i), missing.get(i), newPrice, newCost,
                        missingPositions.get(i), now));
            }

            // The option rows. Kept options first get temporary names, so swapping two names never
            // meets the unique name index halfway.
            existing.stream().filter(option -> !continues.contains(option)).forEach(options::delete);
            // The id's last 12 hex digits are random; its first ones are a timestamp, the same for
            // options created together.
            continues.stream().filter(Objects::nonNull)
                    .forEach(option -> option.change("~" + option.getId().toString().substring(24),
                            option.getValues(), option.getPosition()));
            options.flush();
            for (int i = 0; i < grid.size(); i++) {
                if (continues.get(i) != null) {
                    continues.get(i).change(grid.get(i).name(), grid.get(i).values(), i);
                } else {
                    options.save(new ProductOption(productId, grid.get(i).name(), grid.get(i).values(), i));
                }
            }
            product.touch(now);
            products.saveAndFlush(product);
            return detail(product);
        });
    }

    /** Variants by attributes (order-free maps), each list with active variants first, then by position. */
    private static Map<Map<String, String>, List<ProductVariant>> byAttributes(Map<ProductVariant, Map<String, String>> views) {
        Map<Map<String, String>, List<ProductVariant>> index = new HashMap<>();
        views.forEach((variant, attributes) -> index.computeIfAbsent(attributes, key -> new ArrayList<>()).add(variant));
        index.values().forEach(list -> list.sort(
                Comparator.comparing((ProductVariant v) -> !v.isActive()).thenComparingInt(ProductVariant::getPosition)));
        return index;
    }

    /**
     * Attributes for an archived variant that no other variant of the product has: its own (as
     * stored), else after renames, else, as a last resort, marked with its id.
     */
    private static Map<String, String> freeAttributes(ProductVariant variant, Map<String, String> renamed,
            Set<Map<String, String>> taken) {
        for (Map<String, String> candidate : List.of(new HashMap<>(variant.getAttributes()), renamed)) {
            if (taken.add(candidate)) {
                return candidate;
            }
        }
        Map<String, String> marked = new HashMap<>(variant.getAttributes());
        marked.put("archived", variant.getId().toString());
        taken.add(marked);
        return marked;
    }

    /**
     * Which existing option each input continues, decided once: by {@code id}, else by its tidied
     * name ignoring case. An existing option is continued at most once.
     */
    private static List<ProductOption> matchOptions(List<ProductOption> existing, List<OptionInput> inputs,
            List<VariantGrid.Option> grid) {
        List<ProductOption> continues = new ArrayList<>(java.util.Collections.nCopies(inputs.size(), null));
        Set<UUID> used = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            UUID id = inputs.get(i).id();
            if (id != null) {
                ProductOption option = existing.stream().filter(o -> o.getId().equals(id)).findFirst()
                        .orElseThrow(() -> ApiException.invalidField("options", "field.invalid_value"));
                if (!used.add(id)) {
                    throw ApiException.invalidField("options", "field.option_duplicate");
                }
                continues.set(i, option);
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).id() == null) {
                String name = grid.get(i).name().toLowerCase(Locale.ROOT);
                Optional<ProductOption> option = existing.stream()
                        .filter(o -> !used.contains(o.getId()) && o.getName().toLowerCase(Locale.ROOT).equals(name))
                        .findFirst();
                if (option.isPresent()) {
                    used.add(option.get().getId());
                    continues.set(i, option.get());
                }
            }
        }
        return continues;
    }

    // Variants.

    ProductViews.Variant updateVariant(UUID variantId, VariantCommand command) {
        String requestedSku = command.sku() == null || command.sku().isBlank() ? null : cleanSku(command.sku());
        boolean generateSku = command.sku() != null && command.sku().isBlank();
        return writing(requestedSku != null || generateSku, () -> {
            ProductVariant variant = findVariant(variantId);
            requireVersion(variant.getVersion(), command.version());
            Instant now = clock.instant();
            if (command.name() != null || command.size() != null) {
                renameVariant(variant, command.name(), command.size(), now);
            }
            if (requestedSku != null) {
                variant.changeSku(requestedSku, now);
            } else if (generateSku) {
                Product product = find(variant.getProductId());
                List<VariantGrid.Option> grid = options.findByProductIdOrderByPosition(product.getId()).stream()
                        .map(option -> new VariantGrid.Option(option.getName(), option.getValues())).toList();
                // In option order (the stored attributes have none), and the variant's own SKU does
                // not block it: regenerating "WATCH" gives "WATCH" again.
                Map<String, String> ordered = new LinkedHashMap<>();
                grid.forEach(option -> {
                    if (variant.getAttributes().containsKey(option.name())) {
                        ordered.put(option.name(), variant.getAttributes().get(option.name()));
                    }
                });
                variant.changeSku(newSkus(product.getName(), grid, List.of(ordered), variant.getSku()).get(0), now);
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
            return ProductViews.Variant.of(variant, showCost(), optionOrder(variant.getProductId()),
                    stockLevels.levels(List.of(variant.getId())).get(variant.getId()));
        });
    }

    /**
     * Archives a combination the seller does not make, or restores it. Only a variant that fits the
     * current options can be restored (others come back by adding their option values again), and
     * the last active variant cannot be archived: archive the product instead.
     */
    ProductViews.Variant changeVariantStatus(UUID variantId, ProductStatus status) {
        return growing(() -> {
            ProductVariant variant = findVariant(variantId);
            if (status == ProductStatus.ACTIVE && !fitsOptions(variant)) {
                throw new ApiException(ErrorCode.VARIANT_NOT_IN_OPTIONS);
            }
            if (status == ProductStatus.ARCHIVED && variant.isActive()
                    && variants.findByProductIdOrderByPosition(variant.getProductId()).stream()
                            .filter(ProductVariant::isActive).count() == 1) {
                throw new ApiException(ErrorCode.VARIANT_LAST_ACTIVE);
            }
            variant.changeStatus(status, clock.instant());
            variants.saveAndFlush(variant);
            return ProductViews.Variant.of(variant, showCost(), optionOrder(variant.getProductId()),
                    stockLevels.levels(List.of(variant.getId())).get(variant.getId()));
        });
    }

    /** A variant's stock history, newest first, with who made each change. */
    @Transactional(readOnly = true)
    CursorPage<ProductViews.Movement> history(UUID variantId, String cursor, Integer limit) {
        findVariant(variantId);
        CursorPage<StockHistory> page = stockLevels.history(variantId, cursor, limit);
        Map<UUID, UserDirectory.UserSummary> people = users.summaries(
                page.items().stream().map(StockHistory::userId).filter(Objects::nonNull).distinct().toList());
        return new CursorPage<>(page.items().stream().map(entry -> new ProductViews.Movement(entry.id(), entry.at(),
                entry.type(), entry.onHandChange(), entry.reservedChange(), entry.onHandAfter(), entry.reservedAfter(),
                entry.availableAfter(), entry.referenceType(), entry.referenceId(), entry.note(),
                entry.userId() == null ? null : new ProductViews.Actor(entry.userId(),
                        people.containsKey(entry.userId()) ? people.get(entry.userId()).name() : null))).toList(),
                page.nextCursor());
    }

    // Helpers.

    /** One transaction; SKU-changing writes first take the business's SKU lock. */
    /**
     * A change that may put more variants on sale: under the SKU lock (so such changes count in
     * turn), and refused when it takes the business over its plan's variant limit.
     */
    private <T> T growing(Supplier<T> work) {
        return writing(true, () -> quota.guarded(work, () -> {
            products.flush();
            variants.flush();
        }));
    }

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
        List<ProductVariant> productVariants = variants.findByProductIdOrderByPosition(product.getId());
        Map<UUID, StockLevel> levels = stockLevels.levels(productVariants.stream().map(ProductVariant::getId).toList());
        List<ProductViews.Variant> variantViews = productVariants.stream()
                .map(variant -> ProductViews.Variant.of(variant, showCost, optionOrder, levels.get(variant.getId()))).toList();
        List<ProductViews.Image> imageViews = images.findByProductIdOrderByPosition(product.getId()).stream()
                .map(ProductViews.Image::of).toList();
        return new ProductViews.Detail(product.getId(), product.getName(), product.getDescription(),
                product.getCategoryId(), product.getStatus(), tagsOf(List.of(product.getId())).getOrDefault(product.getId(), List.of()),
                imageViews, optionViews, variantViews, product.getCreatedAt(), product.getVersion() == null ? 0 : product.getVersion());
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
        if (cleaned.length() > CatalogLimits.SKU || cleaned.chars().anyMatch(Character::isWhitespace)) {
            throw ApiException.invalidField("sku", "field.sku_invalid");
        }
        return cleaned;
    }

    private static String description(String text) {
        String cleaned = text == null ? "" : text.strip();
        if (cleaned.length() > CatalogLimits.DESCRIPTION) {
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

    /** The variant's attributes are exactly one value of each current option. */
    private boolean fitsOptions(ProductVariant variant) {
        List<ProductOption> current = options.findByProductIdOrderByPosition(variant.getProductId());
        // A variant made from a row may have only a name or only a size.
        return variant.getAttributes().entrySet().stream().allMatch(attribute -> current.stream()
                .anyMatch(option -> option.getName().equals(attribute.getKey())
                        && option.getValues().contains(attribute.getValue())));
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
