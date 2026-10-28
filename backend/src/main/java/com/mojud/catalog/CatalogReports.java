package com.mojud.catalog;

/** Report figures from the catalog, for the reporting module, in the business of the TenantScope. */
public interface CatalogReports {

    /** Variants on sale at or below their alert level (the "running low" list's length). */
    int lowStockCount();
}
