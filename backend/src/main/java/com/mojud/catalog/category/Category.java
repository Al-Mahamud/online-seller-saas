package com.mojud.catalog.category;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** A product category; top-level when {@link #parentId} is null. Two levels at most. */
@Entity
@Table(name = "categories")
class Category {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private UUID parentId;

    private String name;

    private int position;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    protected Category() {
    }

    Category(UUID parentId, String name, int position, Instant now) {
        this.id = Ids.newId();
        this.parentId = parentId;
        this.name = name;
        this.position = position;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName, Instant now) {
        name = newName;
        updatedAt = now;
    }

    void moveTo(UUID newParentId, int newPosition, Instant now) {
        parentId = newParentId;
        position = newPosition;
        updatedAt = now;
    }

    void placeAt(int newPosition, Instant now) {
        position = newPosition;
        updatedAt = now;
    }

    boolean isTopLevel() {
        return parentId == null;
    }

    UUID getId() {
        return id;
    }

    UUID getParentId() {
        return parentId;
    }

    String getName() {
        return name;
    }

    int getPosition() {
        return position;
    }
}
