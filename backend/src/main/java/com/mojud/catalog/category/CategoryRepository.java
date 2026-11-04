package com.mojud.catalog.category;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("select c from Category c order by c.position, lower(c.name)")
    List<Category> findAllInOrder();

    /** The categories in one group: top-level ones when {@code parentId} is null. */
    @Query("select c from Category c where (:parentId is null and c.parentId is null) or c.parentId = :parentId")
    List<Category> findSiblings(UUID parentId);

    boolean existsByParentId(UUID parentId);

    /**
     * How many products sit directly in each category, archived ones too (they also keep a category
     * from being deleted). Plain SQL gets no Hibernate tenant filter, so the business is passed
     * explicitly; row-level security also applies.
     */
    @Query(nativeQuery = true, value = """
            select p.category_id as id, count(*) as products from products p
            where p.business_id = :businessId and p.category_id is not null
            group by p.category_id""")
    List<Object[]> productCounts(UUID businessId);

    @Query(nativeQuery = true, value = "select count(*) from products p where p.business_id = :businessId and p.category_id = :id")
    int countProducts(UUID businessId, UUID id);

    /** The position after the last category in one group. */
    @Query("select coalesce(max(c.position), -1) + 1 from Category c where (:parentId is null and c.parentId is null) or c.parentId = :parentId")
    int nextPosition(UUID parentId);
}
