package com.mojud.catalog.tag;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface TagRepository extends JpaRepository<Tag, UUID> {

    @Query("select t from Tag t order by lower(t.name)")
    List<Tag> findAllInOrder();
}
