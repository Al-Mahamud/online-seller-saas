package com.mojud.catalog.product;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "product_tags")
@IdClass(ProductTag.Key.class)
class ProductTag implements Persistable<ProductTag.Key> {

    /** New until saved or loaded, so Spring Data inserts it without looking it up first. */
    @Transient
    private boolean isNew = true;

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

    @Override
    public Key getId() {
        return new Key(productId, tagId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    UUID getProductId() {
        return productId;
    }

    UUID getTagId() {
        return tagId;
    }
}
