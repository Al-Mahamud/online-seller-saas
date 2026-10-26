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

    /** The position after the last category in one group. */
    @Query("select coalesce(max(c.position), -1) + 1 from Category c where (:parentId is null and c.parentId is null) or c.parentId = :parentId")
    int nextPosition(UUID parentId);
}
