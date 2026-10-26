package com.mojud.importing;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a column holds. Headers are recognised by the names sellers use, in English and Bangla;
 * an option column (Size, Color, ...) keeps its header as the option's name.
 */
enum ImportField {

    NAME("name", "product", "product name", "products", "title", "item", "item name", "নাম", "পণ্য", "পণ্যের নাম",
            "প্রোডাক্ট", "প্রোডাক্টের নাম"),
    SKU("sku", "code", "product code", "item code", "sku code", "কোড", "পণ্য কোড", "পণ্যের কোড", "এসকেইউ"),
    PRICE("price", "selling price", "sale price", "sell price", "unit price", "mrp", "rate", "দাম", "মূল্য",
            "বিক্রয় মূল্য", "বিক্রি মূল্য", "বিক্রয়মূল্য"),
    COST_PRICE("cost", "cost price", "buying price", "purchase price", "buy price", "ক্রয় মূল্য", "ক্রয়মূল্য", "কেনা দাম",
            "কেনার দাম", "খরচ"),
    STOCK("stock", "quantity", "qty", "in stock", "on hand", "stock quantity", "inventory", "স্টক", "পরিমাণ", "মজুদ"),
    CATEGORY("category", "categories", "ক্যাটাগরি", "ক্যাটেগরি", "বিভাগ"),
    DESCRIPTION("description", "details", "বিবরণ", "বর্ণনা"),
    LOW_STOCK_THRESHOLD("low stock alert", "low stock", "alert level", "reorder level", "সতর্কসীমা", "স্টক সতর্কসীমা"),
    /** A variant option; the column's mapping carries the option's name. */
    OPTION("size", "color", "colour", "material", "style", "pattern", "সাইজ", "মাপ", "রং", "রঙ", "কালার", "উপাদান",
            "ডিজাইন");

    /** Parentheses and marks such as "*" or ":" are not part of a header's name: "Price (৳) *" is "price". */
    private static final Pattern BRACKETS = Pattern.compile("\\(.*?\\)|\\[.*?\\]");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");

    private final Set<String> headers;

    ImportField(String... headers) {
        this.headers = Set.of(headers);
    }

    /** The field a header names, if any. */
    static Optional<ImportField> recognise(String header) {
        String key = key(header);
        for (ImportField field : values()) {
            if (field.headers.contains(key)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    /** A header as an option name: "Size (inch)" is "Size". */
    static String optionName(String header) {
        return BRACKETS.matcher(header).replaceAll(" ").replaceAll("[*:]", " ").strip().replaceAll("\\s+", " ");
    }

    static String key(String header) {
        String text = Normalizer.normalize(header, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return NOT_WORD.matcher(BRACKETS.matcher(text).replaceAll(" ")).replaceAll(" ").strip();
    }

    /** Fields a sheet maps at most once. */
    boolean single() {
        return this != OPTION;
    }

    static final List<ImportField> REQUIRED = List.of(NAME, PRICE);
}
