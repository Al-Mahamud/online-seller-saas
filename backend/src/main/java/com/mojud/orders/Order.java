package com.mojud.orders;

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

/**
 * An order. Money is in poisha and always computed by the server ({@link Totals}); the customer and
 * the delivery address are copied onto it, so later edits to the customer leave it alone.
 */
@Entity(name = "SalesOrder")
@Table(name = "orders")
class Order {

    /** The customer and where the parcel goes, as copied onto the order. */
    record Parties(UUID customerId, String customerName, String customerPhone, String deliveryName, String deliveryPhone,
            String deliveryAddress, Integer deliveryDistrictId, Integer deliveryAreaId) {
    }

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private long number;

    private String numberLabel;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private String source;

    private UUID customerId;

    private String customerName;

    private String customerPhone;

    private long subtotal;

    private long discount;

    private long deliveryCharge;

    private long total;

    private long advancePaid;

    private long codAmount;

    private String deliveryName;

    private String deliveryPhone;

    private String deliveryAddress;

    private Integer deliveryDistrictId;

    private Integer deliveryAreaId;

    private String note;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant confirmedAt;

    private Instant packedAt;

    private Instant shippedAt;

    private Instant deliveredAt;

    private Instant cancelledAt;

    @Version
    private Long version;

    protected Order() {
    }

    Order(long number, String numberLabel, Parties parties, Totals totals, String note, UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.number = number;
        this.numberLabel = numberLabel;
        this.status = OrderStatus.DRAFT;
        this.source = "MANUAL";
        this.createdBy = createdBy;
        this.createdAt = now;
        change(parties, totals, note, now);
    }

    void change(Parties parties, Totals totals, String newNote, Instant now) {
        customerId = parties.customerId();
        customerName = parties.customerName();
        customerPhone = parties.customerPhone();
        deliveryName = parties.deliveryName();
        deliveryPhone = parties.deliveryPhone();
        deliveryAddress = parties.deliveryAddress();
        deliveryDistrictId = parties.deliveryDistrictId();
        deliveryAreaId = parties.deliveryAreaId();
        subtotal = totals.subtotal();
        discount = totals.discount();
        deliveryCharge = totals.deliveryCharge();
        total = totals.total();
        advancePaid = totals.advancePaid();
        codAmount = totals.codAmount();
        note = newNote;
        updatedAt = now;
    }

    Parties parties() {
        return new Parties(customerId, customerName, customerPhone, deliveryName, deliveryPhone, deliveryAddress,
                deliveryDistrictId, deliveryAreaId);
    }

    UUID getId() {
        return id;
    }

    long getNumber() {
        return number;
    }

    String getNumberLabel() {
        return numberLabel;
    }

    OrderStatus getStatus() {
        return status;
    }

    String getSource() {
        return source;
    }

    long getSubtotal() {
        return subtotal;
    }

    long getDiscount() {
        return discount;
    }

    long getDeliveryCharge() {
        return deliveryCharge;
    }

    long getTotal() {
        return total;
    }

    long getAdvancePaid() {
        return advancePaid;
    }

    long getCodAmount() {
        return codAmount;
    }

    String getNote() {
        return note;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    Instant getConfirmedAt() {
        return confirmedAt;
    }

    Instant getPackedAt() {
        return packedAt;
    }

    Instant getShippedAt() {
        return shippedAt;
    }

    Instant getDeliveredAt() {
        return deliveredAt;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    Long getVersion() {
        return version;
    }
}
