package com.mojud.catalog.product;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface ProductRepository extends JpaRepository<Product, UUID> {

    /**
     * Newest first, after the cursor position ({@code afterAt}, {@code afterId}); the first page
     * passes a position after everything (see ProductService). A top-level category also finds
     * products in its subcategories.
     */
    @Query("""
            select p from Product p
            where p.status = :status
              and (:categoryId is null or p.categoryId = :categoryId
                   or p.categoryId in (select c.id from Category c where c.parentId = :categoryId))
              and (:tagId is null or exists (select 1 from ProductTag t where t.productId = p.id and t.tagId = :tagId))
              and (p.createdAt < :afterAt or (p.createdAt = :afterAt and p.id < :afterId))
            order by p.createdAt desc, p.id desc""")
    List<Product> findPage(ProductStatus status, UUID categoryId, UUID tagId, Instant afterAt, UUID afterId, Limit limit);
}
