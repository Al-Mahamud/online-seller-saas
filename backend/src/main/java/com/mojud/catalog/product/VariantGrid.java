package com.mojud.catalog.product;

import com.mojud.catalog.CatalogLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The variant grid of a product: every combination of its option values, in option order and value
 * order. A product without options has one combination, the empty one.
 */
final class VariantGrid {

    /** Up to three options of up to 30 values; the grid itself is capped at 100 variants. */
    static final int MAX_OPTIONS = CatalogLimits.MAX_OPTIONS;
    static final int MAX_VALUES = CatalogLimits.MAX_VALUES;
    static final int MAX_VARIANTS = CatalogLimits.MAX_VARIANTS;

    record Option(String name, List<String> values) {
    }

    private VariantGrid() {
    }

    /** Every combination, each an ordered map of option name to value. */
    static List<Map<String, String>> combinations(List<Option> options) {
        List<Map<String, String>> grid = new ArrayList<>();
        grid.add(new LinkedHashMap<>());
        for (Option option : options) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : grid) {
                for (String value : option.values()) {
                    Map<String, String> combination = new LinkedHashMap<>(partial);
                    combination.put(option.name(), value);
                    next.add(combination);
                }
            }
            grid = next;
        }
        return grid;
    }

    static long size(List<Option> options) {
        long size = 1;
        for (Option option : options) {
            size *= option.values().size();
        }
        return size;
    }

    /** "L / Black"; empty for the plain variant of a product without options. */
    static String label(Map<String, String> attributes) {
        return String.join(" / ", attributes.values());
    }
}
