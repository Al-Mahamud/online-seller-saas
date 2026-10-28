package com.mojud.shared.money;

import java.util.ArrayList;
import java.util.List;

/** Taka as people in Bangladesh write them, for pages people read (slips, invoices). */
public final class Taka {

    private Taka() {
    }

    /** Lakh grouping, two decimals only when there are poisha: 12,34,567 and 2,570.50. */
    public static String lakh(long poisha) {
        String whole = Long.toString(Math.abs(poisha) / 100);
        long rest = Math.abs(poisha) % 100;
        // The last three digits, then pairs: 1,23,45,678.
        List<String> groups = new ArrayList<>();
        int end = whole.length();
        int size = 3;
        while (end > 0) {
            groups.add(0, whole.substring(Math.max(0, end - size), end));
            end -= size;
            size = 2;
        }
        String grouped = String.join(",", groups);
        return (poisha < 0 ? "-" : "") + (rest == 0 ? grouped : grouped + "." + (rest < 10 ? "0" : "") + rest);
    }

    /** ASCII digits as Bangla digits (০-৯). */
    public static String banglaDigits(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            out.append(c >= '0' && c <= '9' ? (char) ('০' + (c - '0')) : c);
        }
        return out.toString();
    }
}
