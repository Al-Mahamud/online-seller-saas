package com.mojud.catalog.product;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ProductTagRepository extends JpaRepository<ProductTag, ProductTag.Key> {

    @Query("""
            select new com.mojud.catalog.product.TagRef(pt.productId, t.id, t.name)
            from ProductTag pt, Tag t
            where t.id = pt.tagId and pt.productId in :productIds
            order by lower(t.name)""")
    List<TagRef> findTags(Collection<UUID> productIds);

    @Modifying
    @Query("delete from ProductTag pt where pt.productId = :productId")
    void deleteByProduct(UUID productId);
}
