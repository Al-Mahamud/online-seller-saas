package com.mojud.orders;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Money the customer paid the business directly: an advance, or a later payment of a shortfall. */
@Entity
@Table(name = "payments")
class Payment {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private UUID orderId;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    private long amount;

    private String reference;

    private Instant receivedAt;

    private UUID recordedBy;

    private Instant createdAt;

    protected Payment() {
    }

    Payment(UUID orderId, PaymentMethod method, long amount, String reference, Instant receivedAt, UUID recordedBy,
            Instant now) {
        this.id = Ids.newId();
        this.orderId = orderId;
        this.method = method;
        this.amount = amount;
        this.reference = reference;
        this.receivedAt = receivedAt;
        this.recordedBy = recordedBy;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    PaymentMethod getMethod() {
        return method;
    }

    long getAmount() {
        return amount;
    }

    String getReference() {
        return reference;
    }

    Instant getReceivedAt() {
        return receivedAt;
    }
}
