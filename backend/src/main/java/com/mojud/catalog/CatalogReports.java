package com.mojud.catalog;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Report figures from the catalog, for the reporting module, in the business of the TenantScope. */
public interface CatalogReports {

    /** Variants on sale at or below their alert level (the "running low" list's length). */
    int lowStockCount();

    boolean hasProducts();

    /**
     * Each variant's category as "Parent > Name" or "Name"; variants of uncategorised products (or
     * unknown ones) are left out.
     */
    Map<UUID, String> categoryPaths(Collection<UUID> variantIds);

    /**
     * Variants whose product name or SKU contains {@code q}, or whose product name is close to it
     * (small typos), archived ones too: for searching what has already happened to goods.
     */
    Set<UUID> variantsMatching(String q);
}
