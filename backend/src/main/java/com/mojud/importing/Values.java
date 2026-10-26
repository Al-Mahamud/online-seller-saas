package com.mojud.importing;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** Reading numbers the way sellers write them: "৳1,200", "Tk. 1200/-", "১২০০", "1,20,000.50". */
final class Values {

    private static final Pattern CURRENCY = Pattern.compile("৳|টাকা|bdt|taka|tk\\.?|/-|/=");
    private static final Pattern GROUPING = Pattern.compile("[,\\s\\p{Z}]");
    private static final Pattern AMOUNT = Pattern.compile("\\d+(\\.\\d{1,2})?");
    private static final Pattern WHOLE = Pattern.compile("\\d+(\\.0+)?");

    private Values() {
    }

    /** Bangla digits (০-৯) as ASCII digits. */
    static String asciiDigits(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= '০' && c <= '৯' ? (char) ('0' + (c - '০')) : c);
        }
        return out.toString();
    }

    /** Poisha, or empty when the text is not an amount of taka with at most 2 decimals. */
    static OptionalLong poisha(String text) {
        String cleaned = GROUPING.matcher(CURRENCY.matcher(asciiDigits(text).toLowerCase(Locale.ROOT)).replaceAll(""))
                .replaceAll("");
        if (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (!AMOUNT.matcher(cleaned).matches() || cleaned.length() > 20) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(new BigDecimal(cleaned).movePointRight(2).longValueExact());
    }

    /** A whole number of 0 or more ("12", "১২", "1,200", or "12.0" as spreadsheets write it). */
    static OptionalInt count(String text) {
        String cleaned = GROUPING.matcher(asciiDigits(text)).replaceAll("");
        if (!WHOLE.matcher(cleaned).matches()) {
            return OptionalInt.empty();
        }
        String whole = cleaned.contains(".") ? cleaned.substring(0, cleaned.indexOf('.')) : cleaned;
        String digits = whole.replaceFirst("^0+(?=\\d)", "");
        return digits.length() > 9 ? OptionalInt.of(Integer.MAX_VALUE) : OptionalInt.of(Integer.parseInt(digits));
    }
}
