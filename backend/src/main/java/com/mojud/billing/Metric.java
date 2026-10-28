package com.mojud.billing;

/** What a plan limits (plans.limits keys). */
public enum Metric {
    /** Variants on sale (active variants of active products), counted live. */
    VARIANTS("variants"),
    /** Orders created in a Dhaka month, counted in usage_counters. */
    ORDERS_PER_MONTH("orders_per_month"),
    /** Staff and managers, with invites still open; the owner is not counted. */
    STAFF_SEATS("staff_seats"),
    /** Rows in one import. */
    IMPORT_ROWS("import_rows"),
    /** How far back reports reach, in days. */
    REPORT_DAYS("report_days");

    private final String key;

    Metric(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
