package com.mojud.customers;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Someone who buys from the business, known by phone number (E.164, unique per business). */
@Entity
@Table(name = "customers")
class Customer {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private String name;

    private String phone;

    private String altPhone;

    private String address;

    private Integer districtId;

    private Integer areaId;

    private String note;

    private int orderCount;

    private Instant lastOrderAt;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    protected Customer() {
    }

    Customer(String name, String phone, Instant now) {
        this.id = Ids.newId();
        this.name = name;
        this.phone = phone;
        this.address = "";
        this.note = "";
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName) {
        name = newName;
    }

    void changePhone(String newPhone) {
        phone = newPhone;
    }

    void changeAltPhone(String newAltPhone) {
        altPhone = newAltPhone;
    }

    void changeAddress(String newAddress) {
        address = newAddress;
    }

    /** {@code areaId} must be in {@code districtId} (or null). */
    void relocate(Integer newDistrictId, Integer newAreaId) {
        districtId = newDistrictId;
        areaId = newAreaId;
    }

    void changeNote(String newNote) {
        note = newNote;
    }

    void touch(Instant now) {
        updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getPhone() {
        return phone;
    }

    String getAltPhone() {
        return altPhone;
    }

    String getAddress() {
        return address;
    }

    Integer getDistrictId() {
        return districtId;
    }

    Integer getAreaId() {
        return areaId;
    }

    String getNote() {
        return note;
    }

    int getOrderCount() {
        return orderCount;
    }

    Instant getLastOrderAt() {
        return lastOrderAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    Long getVersion() {
        return version;
    }
}
