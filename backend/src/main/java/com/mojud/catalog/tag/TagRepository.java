package com.mojud.catalog.tag;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface TagRepository extends JpaRepository<Tag, UUID> {

    @Query("select t from Tag t order by lower(t.name)")
    List<Tag> findAllInOrder();

    /** How many products carry each tag. Plain SQL: the business is passed explicitly; row-level security also applies. */
    @Query(nativeQuery = true, value = """
            select t.tag_id as id, count(*) as products from product_tags t
            where t.business_id = :businessId group by t.tag_id""")
    List<Object[]> productCounts(UUID businessId);

    @Query(nativeQuery = true, value = "select count(*) from product_tags t where t.business_id = :businessId and t.tag_id = :id")
    int countProducts(UUID businessId, UUID id);
}
