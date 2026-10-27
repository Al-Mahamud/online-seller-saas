package com.mojud.importing;

import com.mojud.catalog.CatalogLimits;
import com.mojud.catalog.CatalogLookup;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks data rows against the catalog's rules, so a valid row is one the commit (5b) can apply.
 * Each row on its own (required fields, numbers, lengths), then across rows: a SKU used twice in
 * the file, and rows of one product (same name) that disagree on options or repeat a variant.
 */
final class RowChecker {

    enum Action {
        CREATE, UPDATE
    }

    /** {@code column} is the sheet column (0 is A); {@code code} has a message {@code import.error.<code>}. */
    record RowError(int column, ImportField field, String optionName, String code, List<Object> args) {
    }

    /** One row read through the mapping. Numbers are null when blank or unreadable. */
    record Parsed(int rowNumber, String name, String sku, Long price, Long costPrice, Integer stock, Integer threshold,
            String category, String description, Map<String, String> options, List<RowError> errors) {
    }

    record Checked(int rowNumber, Action action, List<RowError> errors) {

        boolean valid() {
            return errors.isEmpty();
        }
    }

    record Outcome(List<Checked> rows, List<String> newCategories) {

        long count(Action action) {
            return rows.stream().filter(row -> row.valid() && row.action() == action).count();
        }

        long valid() {
            return rows.stream().filter(Checked::valid).count();
        }
    }

    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");
    private static final int MAX_STOCK = 1_000_000;

    private RowChecker() {
    }

    static Outcome check(List<Sheet.Row> rows, Mapping mapping, CatalogLookup catalog) {
        List<Parsed> parsed = rows.stream().map(row -> parse(row, mapping)).toList();
        Categories categories = new Categories(catalog.categories());
        Map<Integer, List<RowError>> errors = new HashMap<>();
        for (Parsed row : parsed) {
            List<RowError> list = new ArrayList<>(row.errors());
            if (row.category() != null) {
                categories.check(row.category(), mapping.of(ImportField.CATEGORY).orElseThrow(), list);
            }
            errors.put(row.rowNumber(), list);
        }
        repeatedSkus(parsed, mapping, errors);
        products(parsed, mapping, errors);
        Set<String> taken = catalog.takenSkus(parsed.stream().map(Parsed::sku).filter(sku -> sku != null).toList());
        Set<String> onSale = catalog.activeProductNames(parsed.stream().map(Parsed::name).filter(name -> name != null).toList());
        List<Checked> checked = parsed.stream().map(row -> {
            List<RowError> list = errors.get(row.rowNumber());
            // As the commit decides: a known SKU, else a product on sale with this name, is updated.
            boolean known = (row.sku() != null && taken.contains(row.sku().toUpperCase(Locale.ROOT)))
                    || (row.name() != null && onSale.contains(row.name().toLowerCase(Locale.ROOT)));
            Action action = known ? Action.UPDATE : Action.CREATE;
            return new Checked(row.rowNumber(), list.isEmpty() ? action : null, List.copyOf(list));
        }).toList();
        return new Outcome(checked, categories.created());
    }

    /** The row's own checks; nothing that needs other rows or the catalog. */
    static Parsed parse(Sheet.Row row, Mapping mapping) {
        List<RowError> errors = new ArrayList<>();
        String name = null;
        String sku = null;
        Long price = null;
        Long cost = null;
        Integer stock = null;
        Integer threshold = null;
        String category = null;
        String description = null;
        Map<String, String> options = new LinkedHashMap<>();
        for (Mapping.Column column : mapping.columns()) {
            String text = row.cell(column.index());
            switch (column.field()) {
                case NAME -> name = name(text, column, CatalogLimits.PRODUCT_NAME, true, errors);
                case SKU -> {
                    if (!text.isEmpty()) {
                        if (text.length() > CatalogLimits.SKU || text.chars().anyMatch(Character::isWhitespace)) {
                            errors.add(error(column, "sku_invalid"));
                        } else {
                            sku = text;
                        }
                    }
                }
                case PRICE -> price = amount(text, column, true, errors);
                case COST_PRICE -> cost = amount(text, column, false, errors);
                case STOCK -> stock = whole(text, column, MAX_STOCK, errors);
                case LOW_STOCK_THRESHOLD -> threshold = whole(text, column, CatalogLimits.MAX_LOW_STOCK_THRESHOLD, errors);
                case CATEGORY -> category = text.isEmpty() ? null : SPACES.matcher(text).replaceAll(" ");
                case DESCRIPTION -> {
                    if (text.length() > CatalogLimits.DESCRIPTION) {
                        errors.add(error(column, "too_long"));
                    } else if (!text.isEmpty()) {
                        description = text;
                    }
                }
                case OPTION -> {
                    String value = name(text, column, CatalogLimits.OPTION_TEXT, false, errors);
                    if (value != null) {
                        options.put(column.optionName(), value);
                    }
                }
            }
        }
        return new Parsed(row.number(), name, sku, price, cost, stock, threshold, category, description, options,
                List.copyOf(errors));
    }

    private static String name(String text, Mapping.Column column, int max, boolean required, List<RowError> errors) {
        String cleaned = SPACES.matcher(Normalizer.normalize(text, Normalizer.Form.NFC)).replaceAll(" ").strip();
        if (cleaned.isEmpty()) {
            if (required) {
                errors.add(error(column, "required"));
            }
            return null;
        }
        if (cleaned.length() > max) {
            errors.add(error(column, "too_long"));
            return null;
        }
        return cleaned;
    }

    private static Long amount(String text, Mapping.Column column, boolean required, List<RowError> errors) {
        if (text.isEmpty()) {
            if (required) {
                errors.add(error(column, "required"));
            }
            return null;
        }
        OptionalLong poisha = Values.poisha(text);
        if (poisha.isEmpty()) {
            errors.add(error(column, "not_a_price"));
            return null;
        }
        if (poisha.getAsLong() > CatalogLimits.MAX_POISHA) {
            errors.add(error(column, "too_large"));
            return null;
        }
        return poisha.getAsLong();
    }

    private static Integer whole(String text, Mapping.Column column, int max, List<RowError> errors) {
        if (text.isEmpty()) {
            return null;
        }
        OptionalInt value = Values.count(text);
        if (value.isEmpty()) {
            errors.add(error(column, "not_a_whole_number"));
            return null;
        }
        if (value.getAsInt() > max) {
            errors.add(error(column, "too_large", max));
            return null;
        }
        return value.getAsInt();
    }

    /** Every row after the first with a SKU (ignoring case) points back at the first. */
    private static void repeatedSkus(List<Parsed> rows, Mapping mapping, Map<Integer, List<RowError>> errors) {
        Map<String, Integer> first = new HashMap<>();
        for (Parsed row : rows) {
            if (row.sku() == null) {
                continue;
            }
            Integer earlier = first.putIfAbsent(row.sku().toUpperCase(Locale.ROOT), row.rowNumber());
            if (earlier != null) {
                errors.get(row.rowNumber()).add(error(mapping.of(ImportField.SKU).orElseThrow(), "sku_repeated", earlier));
            }
        }
    }

    /**
     * Rows with the same product name are variants of one product: they must give the same
     * options, no variant twice, and stay within the catalog's option and variant limits.
     */
    private static void products(List<Parsed> rows, Mapping mapping, Map<Integer, List<RowError>> errors) {
        Map<String, List<Parsed>> byName = rows.stream().filter(row -> row.name() != null)
                .collect(Collectors.groupingBy(row -> row.name().toLowerCase(Locale.ROOT), LinkedHashMap::new,
                        Collectors.toList()));
        Mapping.Column nameColumn = mapping.of(ImportField.NAME).orElseThrow();
        for (List<Parsed> product : byName.values()) {
            Parsed first = product.get(0);
            Set<String> optionNames = lower(first.options().keySet());
            Map<Map<String, String>, Integer> variants = new HashMap<>();
            Map<String, Set<String>> values = new TreeMap<>();
            for (Parsed row : product) {
                if (!lower(row.options().keySet()).equals(optionNames)) {
                    errors.get(row.rowNumber()).add(error(nameColumn, "options_mismatch", first.rowNumber()));
                    continue;
                }
                Map<String, String> variant = row.options().entrySet().stream().collect(Collectors.toMap(
                        e -> e.getKey().toLowerCase(Locale.ROOT), e -> e.getValue().toLowerCase(Locale.ROOT)));
                Integer earlier = variants.putIfAbsent(variant, row.rowNumber());
                if (earlier != null) {
                    errors.get(row.rowNumber()).add(error(nameColumn, "variant_repeated", earlier));
                }
                variant.forEach((option, value) -> values.computeIfAbsent(option, key -> new HashSet<>()).add(value));
            }
            String tooBig = values.values().stream().anyMatch(set -> set.size() > CatalogLimits.MAX_VALUES)
                    ? "too_many_values"
                    : variants.size() > CatalogLimits.MAX_VARIANTS ? "too_many_variants" : null;
            if (tooBig != null) {
                product.forEach(row -> errors.get(row.rowNumber()).add(error(nameColumn, tooBig)));
            }
        }
    }

    private static Set<String> lower(Set<String> names) {
        return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    static RowError error(Mapping.Column column, String code, Object... args) {
        return new RowError(column.index(), column.field(), column.optionName(), code, List.of(args));
    }

    /**
     * Category text against the business's categories: "Name" or "Parent > Name" (categories are
     * two levels deep). A name that matches nothing is a new category, made on commit.
     */
    private static final class Categories {

        private final List<CatalogLookup.CategoryRef> existing;
        private final Map<String, String> created = new TreeMap<>();

        Categories(List<CatalogLookup.CategoryRef> existing) {
            this.existing = existing;
        }

        void check(String text, Mapping.Column column, List<RowError> errors) {
            String[] parts = text.split(">", -1);
            if (parts.length > 2) {
                errors.add(error(column, "category_too_deep"));
                return;
            }
            String parent = parts.length == 2 ? parts[0].strip() : null;
            String name = parts[parts.length - 1].strip();
            if (name.isEmpty() || (parent != null && parent.isEmpty())) {
                errors.add(error(column, "category_invalid"));
                return;
            }
            if (name.length() > CatalogLimits.CATEGORY_NAME || (parent != null && parent.length() > CatalogLimits.CATEGORY_NAME)) {
                errors.add(error(column, "too_long"));
                return;
            }
            if (parent == null) {
                long matches = existing.stream().filter(c -> same(c.name(), name)).count();
                if (matches > 1) {
                    errors.add(error(column, "category_ambiguous"));
                } else if (matches == 0) {
                    created.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                }
                return;
            }
            boolean found = existing.stream().anyMatch(c -> same(c.name(), name) && c.parentName() != null
                    && same(c.parentName(), parent));
            if (found) {
                return;
            }
            boolean parentIsSub = existing.stream().anyMatch(c -> same(c.name(), parent) && c.parentId() != null)
                    && existing.stream().noneMatch(c -> same(c.name(), parent) && c.parentId() == null);
            if (parentIsSub) {
                errors.add(error(column, "category_too_deep"));
                return;
            }
            created.putIfAbsent((parent + " > " + name).toLowerCase(Locale.ROOT), parent + " > " + name);
        }

        List<String> created() {
            return List.copyOf(created.values());
        }

        private static boolean same(String a, String b) {
            return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
        }
    }
}
