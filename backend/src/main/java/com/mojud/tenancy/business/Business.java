package com.mojud.tenancy.business;

import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.text.Text;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A seller's business. Its id is the tenant key: row-level security shows a business only inside
 * its own {@code TenantScope}.
 */
@Entity
@Table(name = "businesses")
class Business {

    @Id
    private UUID id;

    private String name;

    private String phone;

    private String address;

    @Enumerated(EnumType.STRING)
    private BusinessStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    protected Business() {
    }

    Business(UUID id, String name, Instant now) {
        this.id = id;
        this.name = Text.requireNonBlank(name, "name");
        this.status = BusinessStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName, Instant now) {
        name = Text.requireNonBlank(newName, "name");
        updatedAt = now;
    }

    /** A null phone or blank address clears it. */
    void changeContact(PhoneNumber newPhone, String newAddress, Instant now) {
        phone = newPhone == null ? null : newPhone.e164();
        address = newAddress == null || newAddress.isBlank() ? null : newAddress.strip();
        updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    Optional<PhoneNumber> getPhone() {
        return Optional.ofNullable(phone).map(PhoneNumber::new);
    }

    String getAddress() {
        return address;
    }
}
