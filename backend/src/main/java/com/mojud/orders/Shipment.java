package com.mojud.orders;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** A parcel handed to a courier. One order has one, or more after a failed delivery (8c). */
@Entity
@Table(name = "shipments")
class Shipment {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private UUID orderId;

    private String courier;

    private String trackingNo;

    private long courierCharge;

    private String status;

    private long codExpected;

    private Long codCollected;

    private String codStatus;

    private UUID shippedBy;

    private Instant shippedAt;

    private Instant deliveredAt;

    private Instant settledAt;

    protected Shipment() {
    }

    Shipment(UUID orderId, String courier, String trackingNo, long courierCharge, long codExpected, UUID shippedBy,
            Instant now) {
        this.id = Ids.newId();
        this.orderId = orderId;
        this.courier = courier;
        this.trackingNo = trackingNo;
        this.courierCharge = courierCharge;
        this.status = "IN_TRANSIT";
        this.codExpected = codExpected;
        this.codStatus = codExpected > 0 ? "WITH_COURIER" : "NONE";
        this.shippedBy = shippedBy;
        this.shippedAt = now;
    }

    void delivered(Instant now) {
        status = "DELIVERED";
        deliveredAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getOrderId() {
        return orderId;
    }

    String getCourier() {
        return courier;
    }

    String getTrackingNo() {
        return trackingNo;
    }

    long getCourierCharge() {
        return courierCharge;
    }

    String getStatus() {
        return status;
    }

    long getCodExpected() {
        return codExpected;
    }

    Long getCodCollected() {
        return codCollected;
    }

    String getCodStatus() {
        return codStatus;
    }

    Instant getShippedAt() {
        return shippedAt;
    }

    Instant getDeliveredAt() {
        return deliveredAt;
    }

    Instant getSettledAt() {
        return settledAt;
    }
}
