package com.mojud.catalog.product;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Readable SKUs for variants whose SKU was left blank: the product name's first letters and digits,
 * then a short code per option value, e.g. {@code KURTI-L-BLA}. Names or values without Latin
 * letters or digits (Bangla) fall back to a random code and the value's place in its option.
 * A clash with an SKU the business already has gets {@code -2}, {@code -3}, ... appended.
 */
final class SkuGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private SkuGenerator() {
    }

    /** The common start of every generated SKU of this product. */
    static String base(String productName) {
        String letters = asciiCode(productName, 8);
        if (!letters.isEmpty()) {
            return letters;
        }
        StringBuilder random = new StringBuilder("P");
        for (int i = 0; i < 5; i++) {
            random.append(CODE_CHARACTERS.charAt(RANDOM.nextInt(CODE_CHARACTERS.length())));
        }
        return random.toString();
    }

    /**
     * One SKU per combination, none of them in {@code taken} (upper-case SKUs the business has) or
     * repeated among themselves.
     *
     * @param valuePositions for each option name, its values in order (for the fallback codes)
     */
    static List<String> generate(String base, List<Map<String, String>> combinations,
            Map<String, List<String>> valuePositions, Set<String> taken) {
        Set<String> used = new HashSet<>(taken);
        List<String> skus = new ArrayList<>();
        for (Map<String, String> combination : combinations) {
            StringBuilder candidate = new StringBuilder(base);
            combination.forEach((option, value) -> {
                String code = asciiCode(value, 3);
                candidate.append('-').append(code.isEmpty()
                        ? "V" + (valuePositions.getOrDefault(option, List.of()).indexOf(value) + 1)
                        : code);
            });
            skus.add(free(candidate.toString(), used));
        }
        return skus;
    }

    /** {@code candidate}, or with the first free {@code -n} suffix; remembered as used. */
    static String free(String candidate, Set<String> used) {
        String sku = candidate;
        for (int n = 2; used.contains(sku.toUpperCase(Locale.ROOT)); n++) {
            sku = candidate + "-" + n;
        }
        used.add(sku.toUpperCase(Locale.ROOT));
        return sku;
    }

    private static String asciiCode(String text, int length) {
        StringBuilder code = new StringBuilder();
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                code.append(c);
                if (code.length() == length) {
                    break;
                }
            }
        }
        return code.toString();
    }
}
