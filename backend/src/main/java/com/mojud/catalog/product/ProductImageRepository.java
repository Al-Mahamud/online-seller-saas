package com.mojud.catalog.product;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    List<ProductImage> findByProductIdOrderByPosition(UUID productId);

    List<ProductImage> findByProductIdInOrderByPosition(Collection<UUID> productIds);
}
