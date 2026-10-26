package com.mojud.catalog.product;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "product_tags")
@IdClass(ProductTag.Key.class)
class ProductTag {

    record Key(UUID productId, UUID tagId) implements Serializable {

        @SuppressWarnings("unused")
        Key() {
            this(null, null);
        }
    }

    @Id
    private UUID productId;

    @Id
    private UUID tagId;

    @TenantId
    private UUID businessId;

    protected ProductTag() {
    }

    ProductTag(UUID productId, UUID tagId) {
        this.productId = productId;
        this.tagId = tagId;
    }

    UUID getProductId() {
        return productId;
    }

    UUID getTagId() {
        return tagId;
    }
}
