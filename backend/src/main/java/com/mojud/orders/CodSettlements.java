package com.mojud.orders;

import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * COD money with couriers (implementation-plan.md 8b): what each shipment was to collect, what it
 * collected, and settling a courier's payout, which covers many shipments at once.
 */
@Service
class CodSettlements {

    static final int MAX_LIST = 500;
    static final int MAX_SETTLE = 500;

    /** Money in poisha. */
    record Row(UUID id, UUID orderId, String orderNumberLabel, String courier, String trackingNo, String status,
            long codExpected, @Nullable Long codCollected, String codStatus, Instant shippedAt, @Nullable Instant deliveredAt,
            @Nullable Instant settledAt) {
    }

    /** {@code amount}: the COD collected on the shipments settled, in poisha. */
    record Settlement(String courier, int shipments, long amount, Instant settledAt) {
    }

    private final ShipmentRepository shipments;
    private final OrderRepository orders;
    private final AuditTrail audit;
    private final Clock clock;

    CodSettlements(ShipmentRepository shipments, OrderRepository orders, AuditTrail audit, Clock clock) {
        this.shipments = shipments;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Newest first, up to 500. {@code codStatus} WITH_COURIER and {@code status} DELIVERED is what
     * a courier owes; WITH_COURIER and IN_TRANSIT is still to be collected.
     */
    @Transactional(readOnly = true)
    List<Row> list(String status, String codStatus, String courier) {
        List<Shipment> found = shipments.findFiltered(status == null ? "" : status, codStatus == null ? "" : codStatus,
                courier == null ? "" : courier.strip().toLowerCase(Locale.ROOT), Limit.of(MAX_LIST));
        Map<UUID, Order> byId = orders.findAllById(found.stream().map(Shipment::getOrderId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        return found.stream().map(s -> new Row(s.getId(), s.getOrderId(), byId.get(s.getOrderId()).getNumberLabel(),
                s.getCourier(), s.getTrackingNo(), s.getStatus(), s.getCodExpected(), s.getCodCollected(), s.getCodStatus(),
                s.getShippedAt(), s.getDeliveredAt(), s.getSettledAt())).toList();
    }

    /**
     * Marks these shipments' COD as paid out to the business: all or none, all of one courier, each
     * delivered with its collected cash still with the courier. Rows are locked, so a shipment is
     * settled once even when two people settle the same payout.
     */
    @Transactional
    Settlement settle(List<UUID> shipmentIds) {
        CurrentBusiness me = CurrentBusiness.require();
        Set<UUID> ids = new HashSet<>(shipmentIds);
        if (ids.isEmpty() || ids.size() > MAX_SETTLE) {
            throw ApiException.invalidField("shipmentIds", "field.invalid_value");
        }
        List<Shipment> locked = shipments.findLocked(ids);
        if (locked.size() != ids.size()) {
            throw ApiException.notFound();
        }
        Set<String> couriers = locked.stream().map(s -> s.getCourier().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (couriers.size() > 1) {
            throw ApiException.invalidField("shipmentIds", "field.settle_one_courier");
        }
        List<UUID> notSettleable = locked.stream()
                .filter(s -> !"DELIVERED".equals(s.getStatus()) || !"WITH_COURIER".equals(s.getCodStatus()))
                .map(Shipment::getId).sorted().toList();
        if (!notSettleable.isEmpty()) {
            throw new ApiException(ErrorCode.COD_NOT_SETTLEABLE).withDetail("shipmentIds", notSettleable);
        }
        Instant now = clock.instant();
        locked.forEach(s -> s.settled(now));
        shipments.flush();
        long amount = locked.stream().mapToLong(Shipment::getCodCollected).sum();
        String courier = locked.get(0).getCourier();
        audit.record(me.businessId(), me.userId(), "cod.settled", "business", me.businessId(), "courier", courier,
                "shipments", locked.size(), "amount", amount);
        return new Settlement(courier, locked.size(), amount, now);
    }
}
