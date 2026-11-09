package com.mojud.catalog.product;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Readable SKUs for variants whose SKU was left blank: the product name's first letters and digits,
 * then a short code per option value, e.g. {@code KURTI-L-BLA}. Names or values without Latin
 * letters or digits (Bangla) fall back to a code kept per product and the value's place in its option.
 * A clash with an SKU the business already has gets {@code -2}, {@code -3}, ... appended.
 */
final class SkuGenerator {

    private static final String CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** A code base at the start of a generated SKU: P and five code characters, then "-" or the end. */
    private static final Pattern CODE_START = Pattern.compile("(P[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5})(?=-|$)");

    private SkuGenerator() {
    }

    /**
     * The common start of every generated SKU of this product. A name without Latin letters or digits
     * (Bangla) gives a code instead: the one its variants already start with, so a variant added later
     * matches them, or else one made from the product's id, the same every time.
     */
    static String base(String productName, UUID productId, Collection<String> existingSkus) {
        String letters = asciiCode(productName, 8);
        if (!letters.isEmpty()) {
            return letters;
        }
        for (String sku : existingSkus) {
            Matcher code = CODE_START.matcher(sku == null ? "" : sku);
            if (code.lookingAt()) {
                return code.group(1);
            }
        }
        StringBuilder code = new StringBuilder("P");
        long bits = productId.getLeastSignificantBits();
        for (int i = 0; i < 5; i++) {
            code.append(CODE_CHARACTERS.charAt((int) Long.remainderUnsigned(bits, CODE_CHARACTERS.length())));
            bits = Long.divideUnsigned(bits, CODE_CHARACTERS.length());
        }
        return code.toString();
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
