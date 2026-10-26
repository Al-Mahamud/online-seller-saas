package com.mojud.catalog.product;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.data.domain.Persistable;

/** An uploaded image shown on a product, optionally for one of its variants. */
@Entity
@Table(name = "product_images")
class ProductImage implements Persistable<UUID> {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private UUID productId;

    private UUID fileId;

    private UUID variantId;

    private int position;

    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected ProductImage() {
    }

    ProductImage(UUID productId, UUID fileId, UUID variantId, int position, Instant now) {
        this.id = Ids.newId();
        this.productId = productId;
        this.fileId = fileId;
        this.variantId = variantId;
        this.position = position;
        this.createdAt = now;
    }

    void showFor(UUID newVariantId) {
        variantId = newVariantId;
    }

    void placeAt(int newPosition) {
        position = newPosition;
    }

    @Override
    public UUID getId() {
        return id;
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

    UUID getFileId() {
        return fileId;
    }

    UUID getVariantId() {
        return variantId;
    }

    int getPosition() {
        return position;
    }
}
