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

    /**
     * Products whose name or an SKU contains {@code pattern} (a LIKE pattern, ignoring case), or
     * whose name is close to {@code q} (trigram word similarity, for small typos). Best matches
     * first. Plain SQL gets no Hibernate tenant filter, so the business is passed explicitly; row-
     * level security also applies.
     */
    @Query(nativeQuery = true, value = """
            select p.* from products p
            where p.business_id = :businessId and p.status = :status
              and (cast(:categoryId as uuid) is null or p.category_id = :categoryId
                   or p.category_id in (select c.id from categories c where c.business_id = :businessId and c.parent_id = :categoryId))
              and (cast(:tagId as uuid) is null or exists (select 1 from product_tags t
                   where t.business_id = :businessId and t.product_id = p.id and t.tag_id = :tagId))
              and (lower(p.name) like :pattern
                   or word_similarity(lower(:q), lower(p.name)) >= 0.4
                   or exists (select 1 from product_variants v
                              where v.business_id = :businessId and v.product_id = p.id and upper(v.sku) like upper(:pattern)))
            order by (lower(p.name) like :pattern) desc,
                     word_similarity(lower(:q), lower(p.name)) desc,
                     p.created_at desc
            limit :limit""")
    List<Product> search(UUID businessId, String status, UUID categoryId, UUID tagId, String q, String pattern, int limit);
}
