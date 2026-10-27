package com.mojud.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * A variant as an order sees it. {@code label} is its option values in the product's option
     * order ("M / Black"; empty without options); {@code sellable}: it and its product are active.
     */
    record VariantInfo(UUID variantId, UUID productId, String productName, String label, String sku, long price,
            Long costPrice, boolean sellable) {
    }

    /** The variants found among {@code ids}; unknown ids (or another business's) are left out. */
    Map<UUID, VariantInfo> variants(Collection<UUID> ids);

    /** Which of {@code names} are names of products on sale, lower-cased. */
    Set<String> activeProductNames(Collection<String> names);
}
