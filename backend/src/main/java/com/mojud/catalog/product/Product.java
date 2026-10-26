package com.mojud.catalog.product;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "products")
class Product {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private UUID categoryId;

    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    private ProductStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    protected Product() {
    }

    Product(String name, String description, UUID categoryId, Instant now) {
        this.id = Ids.newId();
        this.name = name;
        this.description = description;
        this.categoryId = categoryId;
        this.status = ProductStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName, Instant now) {
        name = newName;
        updatedAt = now;
    }

    void describe(String newDescription, Instant now) {
        description = newDescription;
        updatedAt = now;
    }

    void categorise(UUID newCategoryId, Instant now) {
        categoryId = newCategoryId;
        updatedAt = now;
    }

    void changeStatus(ProductStatus newStatus, Instant now) {
        status = newStatus;
        updatedAt = now;
    }

    /** Marks the product changed when only its options or variants changed, for optimistic locking. */
    void touch(Instant now) {
        updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCategoryId() {
        return categoryId;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    ProductStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Long getVersion() {
        return version;
    }
}
