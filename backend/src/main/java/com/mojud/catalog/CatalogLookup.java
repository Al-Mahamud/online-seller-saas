package com.mojud.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only catalog facts other modules (the importer) need, for the business being worked in. */
public interface CatalogLookup {

    /** {@code parentName} is null for a top-level category. */
    record CategoryRef(UUID id, String name, UUID parentId, String parentName) {
    }

    List<CategoryRef> categories();

    /** Which of {@code skus} are taken, upper-cased (SKUs are unique ignoring case). */
    Set<String> takenSkus(Collection<String> skus);
}
