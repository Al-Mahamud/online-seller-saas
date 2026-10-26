package com.mojud.catalog.product;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    List<ProductVariant> findByProductIdOrderByPosition(UUID productId);

    List<ProductVariant> findByProductIdInOrderByPosition(Collection<UUID> productIds);

    /** Upper-case SKUs of the business that start with {@code prefix}, for picking free ones. */
    @Query("select upper(v.sku) from ProductVariant v where upper(v.sku) like concat(upper(:prefix), '%')")
    List<String> findSkusStartingWith(String prefix);
}
