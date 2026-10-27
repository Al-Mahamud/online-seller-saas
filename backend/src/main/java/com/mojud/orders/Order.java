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

    private long amountPaid;

    private long returnCredit;

    private Instant returnedAt;

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

    @Enumerated(EnumType.STRING)
    private StockState stockState;

    private String cancelReason;

    @Version
    private Long version;

    protected Order() {
    }

    Order(long number, String numberLabel, Parties parties, Totals totals, String note, UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.number = number;
        this.numberLabel = numberLabel;
        this.status = OrderStatus.DRAFT;
        this.stockState = StockState.NONE;
        this.source = "MANUAL";
        this.createdBy = createdBy;
        this.createdAt = now;
        change(parties, totals, note, now);
        this.amountPaid = totals.advancePaid();
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

    /**
     * The customer paid the business directly. Before the parcel leaves it is an advance (the
     * courier collects that much less); after delivery it pays off what is still owed.
     */
    void paid(long amount, Instant now) {
        if (status == OrderStatus.DELIVERED) {
            amountPaid += amount;
        } else {
            advancePaid += amount;
            codAmount -= amount;
            amountPaid += amount;
        }
        updatedAt = now;
    }

    /** What the customer owes for what they kept: the total less what returns took off. */
    long netTotal() {
        return total - returnCredit;
    }

    /** What the customer still owes; negative when they paid more than they now owe (a refund is due). */
    long balanceDue() {
        return netTotal() - amountPaid;
    }

    /** Goods came back: the customer no longer owes {@code credit} for them. */
    void credit(long credit, Instant now) {
        returnCredit = Math.min(total, returnCredit + credit);
        updatedAt = now;
    }

    /**
     * Money given back. Before anything was delivered it was an advance: the advance shrinks, and a
     * re-shipment collects that much more.
     */
    void refund(long amount, Instant now) {
        if (deliveredAt == null) {
            advancePaid -= amount;
            codAmount += amount;
        }
        amountPaid -= amount;
        updatedAt = now;
    }

    /** The parcel came back undelivered: the customer owes nothing for it, and the stock is back. */
    void failedDelivery(Instant now) {
        status = OrderStatus.RETURNED;
        stockState = StockState.NONE;
        returnCredit = total;
        returnedAt = now;
        updatedAt = now;
    }

    /** Everything delivered has come back. */
    void allReturned(Instant now) {
        status = OrderStatus.RETURNED;
        returnedAt = now;
        updatedAt = now;
    }

    /** Status changes: OrderTransitions only. */
    void confirm(StockState taken, Instant now) {
        status = OrderStatus.CONFIRMED;
        stockState = taken;
        confirmedAt = now;
        updatedAt = now;
    }

    void pack(Instant now) {
        status = OrderStatus.PACKED;
        packedAt = now;
        updatedAt = now;
    }

    /** Stock leaves on_hand at shipping at the latest. A re-shipment after a failed delivery is owed in full again. */
    void ship(Instant now) {
        if (status == OrderStatus.RETURNED) {
            returnCredit = 0;
        }
        status = OrderStatus.SHIPPED;
        stockState = StockState.DEDUCTED;
        shippedAt = now;
        updatedAt = now;
    }

    /** {@code codCollected}: what the courier took from the customer, in poisha. */
    void deliver(long codCollected, Instant now) {
        status = OrderStatus.DELIVERED;
        amountPaid += codCollected;
        deliveredAt = now;
        updatedAt = now;
    }

    void cancel(String reason, Instant now) {
        status = OrderStatus.CANCELLED;
        stockState = StockState.NONE;
        cancelReason = reason;
        cancelledAt = now;
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

    long getAmountPaid() {
        return amountPaid;
    }

    /** UNPAID, PARTIAL, PAID, or REFUND_DUE (paid more than is now owed), against the net total. */
    String getPaymentStatus() {
        long owed = netTotal();
        if (amountPaid > owed) {
            return "REFUND_DUE";
        }
        return amountPaid == owed ? "PAID" : amountPaid == 0 ? "UNPAID" : "PARTIAL";
    }

    long getReturnCredit() {
        return returnCredit;
    }

    Instant getReturnedAt() {
        return returnedAt;
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

    StockState getStockState() {
        return stockState;
    }

    String getCancelReason() {
        return cancelReason;
    }

    Long getVersion() {
        return version;
    }
}
