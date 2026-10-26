package com.mojud.catalog;

/** The catalog's limits, shared with the importer so a sheet is checked exactly like the forms. */
public final class CatalogLimits {

    public static final int PRODUCT_NAME = 200;
    public static final int DESCRIPTION = 5000;
    public static final int CATEGORY_NAME = 80;
    /** Option names and values, such as "Size" and "XL". */
    public static final int OPTION_TEXT = 30;
    public static final int MAX_OPTIONS = 3;
    public static final int MAX_VALUES = 30;
    public static final int MAX_VARIANTS = 100;
    public static final int SKU = 64;
    /** Prices up to ৳10,000,000,000 (in poisha). */
    public static final long MAX_POISHA = 1_000_000_000_000L;
    public static final int MAX_LOW_STOCK_THRESHOLD = 100_000;

    private CatalogLimits() {
    }
}
