package com.mojud.catalog.product;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductOptionRepository extends JpaRepository<ProductOption, UUID> {

    List<ProductOption> findByProductIdOrderByPosition(UUID productId);
}
