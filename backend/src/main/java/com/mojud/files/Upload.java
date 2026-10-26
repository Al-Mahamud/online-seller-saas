package com.mojud.files;

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

/** A processed image, stored as {@code <business>/<id>/large.<ext>} and {@code small.<ext>}. */
@Entity
@Table(name = "uploads")
class Upload implements Persistable<UUID> {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private String contentType;

    private int width;

    private int height;

    private int largeBytes;

    private int smallBytes;

    private UUID uploadedBy;

    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Upload() {
    }

    Upload(UUID id, String contentType, int width, int height, int largeBytes, int smallBytes, UUID uploadedBy,
            Instant now) {
        this.id = id;
        this.contentType = contentType;
        this.width = width;
        this.height = height;
        this.largeBytes = largeBytes;
        this.smallBytes = smallBytes;
        this.uploadedBy = uploadedBy;
        this.createdAt = now;
    }

    /** The storage key of one size; the extension follows the content type. */
    String key(String size) {
        return businessId + "/" + id + "/" + size + "." + extension(contentType);
    }

    static String extension(String contentType) {
        return contentType.equals("image/png") ? "png" : "jpg";
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

    UUID getBusinessId() {
        return businessId;
    }

    String getContentType() {
        return contentType;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }
}
