package com.mojud.orders;

import com.mojud.delivery.GeoDirectory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What the API returns for orders. Money in poisha; cost only for managers and owners. */
final class OrderViews {

    record GeoRef(int id, String nameEn, String nameBn) {
    }

    record CustomerRef(UUID id, String name, String phone) {
    }

    record Delivery(String name, String phone, String address, @Nullable GeoRef district, @Nullable GeoRef area) {
    }

    record Item(UUID id, UUID variantId, String productName, String variantLabel, String sku, int quantity, long unitPrice,
            @Nullable Long unitCost, long amount) {
    }

    /** {@code courierCharge}, {@code codExpected}, {@code codCollected} in poisha. */
    record ShipmentView(UUID id, String courier, String trackingNo, long courierCharge, String status, long codExpected,
            @Nullable Long codCollected, String codStatus, Instant shippedAt, @Nullable Instant deliveredAt,
            @Nullable Instant settledAt) {
    }

    record PaymentView(UUID id, PaymentMethod method, long amount, String reference, Instant receivedAt) {
    }

    record Summary(UUID id, long number, String numberLabel, OrderStatus status, String customerName, String customerPhone,
            int itemCount, long total, long codAmount, String paymentStatus, Instant createdAt) {
    }

    record Detail(UUID id, long number, String numberLabel, OrderStatus status, String source, CustomerRef customer,
            Delivery delivery, List<Item> items, long subtotal, long discount, long deliveryCharge, long total,
            long advancePaid, long codAmount, long returnCredit, long netTotal, long amountPaid, long balanceDue,
            String paymentStatus, List<PaymentView> payments, List<OrderReturns.View> returns, List<OrderEvents.View> events,
            String note, UUID createdBy, Instant createdAt, Instant updatedAt,
            @Nullable Instant confirmedAt, @Nullable Instant packedAt, @Nullable Instant shippedAt,
            @Nullable Instant deliveredAt, @Nullable Instant cancelledAt, @Nullable Instant returnedAt,
            @Nullable String cancelReason, StockState stockState, List<ShipmentView> shipments, Long version) {
    }

    private OrderViews() {
    }

    static Summary summary(Order order, int itemCount) {
        Order.Parties parties = order.parties();
        return new Summary(order.getId(), order.getNumber(), order.getNumberLabel(), order.getStatus(),
                parties.customerName(), parties.customerPhone(), itemCount, order.getTotal(), order.getCodAmount(),
                order.getPaymentStatus(), order.getCreatedAt());
    }

    static Detail detail(Order order, List<OrderItem> items, List<Shipment> shipments, List<Payment> payments,
            List<OrderReturns.View> returns, List<OrderEvents.View> events, GeoDirectory geo, boolean showCost) {
        Order.Parties parties = order.parties();
        GeoRef district = parties.deliveryDistrictId() == null ? null : geo.district(parties.deliveryDistrictId())
                .map(d -> new GeoRef(d.id(), d.nameEn(), d.nameBn())).orElse(null);
        GeoRef area = parties.deliveryAreaId() == null ? null : geo.area(parties.deliveryAreaId())
                .map(a -> new GeoRef(a.id(), a.nameEn(), a.nameBn())).orElse(null);
        return new Detail(order.getId(), order.getNumber(), order.getNumberLabel(), order.getStatus(), order.getSource(),
                new CustomerRef(parties.customerId(), parties.customerName(), parties.customerPhone()),
                new Delivery(parties.deliveryName(), parties.deliveryPhone(), parties.deliveryAddress(), district, area),
                items.stream().map(item -> new Item(item.getId(), item.getVariantId(), item.getProductName(),
                        item.getVariantLabel(), item.getSku(), item.getQuantity(), item.getUnitPrice(),
                        showCost ? item.getUnitCost() : null, item.getQuantity() * item.getUnitPrice())).toList(),
                order.getSubtotal(), order.getDiscount(), order.getDeliveryCharge(), order.getTotal(),
                order.getAdvancePaid(), order.getCodAmount(), order.getReturnCredit(), order.netTotal(),
                order.getAmountPaid(), order.balanceDue(), order.getPaymentStatus(),
                payments.stream().map(p -> new PaymentView(p.getId(), p.getMethod(), p.getAmount(), p.getReference(),
                        p.getReceivedAt())).toList(), returns, events, order.getNote(), order.getCreatedBy(), order.getCreatedAt(),
                order.getUpdatedAt(), order.getConfirmedAt(), order.getPackedAt(), order.getShippedAt(),
                order.getDeliveredAt(), order.getCancelledAt(), order.getReturnedAt(), order.getCancelReason(), order.getStockState(),
                shipments.stream().map(s -> new ShipmentView(s.getId(), s.getCourier(), s.getTrackingNo(),
                        s.getCourierCharge(), s.getStatus(), s.getCodExpected(), s.getCodCollected(), s.getCodStatus(),
                        s.getShippedAt(), s.getDeliveredAt(), s.getSettledAt())).toList(),
                order.getVersion());
    }
}
