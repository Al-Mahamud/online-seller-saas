package com.mojud.catalog.tag;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** A free label for products ("eid", "new arrival"); names unique per business, ignoring case. */
@Entity
@Table(name = "tags")
class Tag {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private String name;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    protected Tag() {
    }

    Tag(String name, Instant now) {
        this.id = Ids.newId();
        this.name = name;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName, Instant now) {
        name = newName;
        updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }
}
