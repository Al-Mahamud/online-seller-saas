package com.mojud.orders;

import com.mojud.customers.CustomerDirectory;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.BusinessDefaults;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place an order's status changes (backend-plan.md §6). Each change locks the order row,
 * checks the status it finds there, moves stock and writes the event and audit entry, all in one
 * transaction: two people pressing "cancel" at once get one cancel and one 409.
 */
@Service
class OrderTransitions {

    static final int MAX_REASON = 500;
    static final int MAX_COURIER = 50;
    static final int MAX_TRACKING = 100;
    private static final Set<OrderStatus> CANCELLABLE = Set.of(OrderStatus.DRAFT, OrderStatus.CONFIRMED, OrderStatus.PACKED);

    private final OrderRepository orders;
    private final ShipmentRepository shipments;
    private final OrderService service;
    private final OrderStock stock;
    private final OrderEvents events;
    private final CustomerDirectory customers;
    private final BusinessDefaults settings;
    private final AuditTrail audit;
    private final Clock clock;

    OrderTransitions(OrderRepository orders, ShipmentRepository shipments, OrderService service, OrderStock stock, OrderEvents events,
            CustomerDirectory customers, BusinessDefaults settings, AuditTrail audit, Clock clock) {
        this.orders = orders;
        this.shipments = shipments;
        this.service = service;
        this.stock = stock;
        this.events = events;
        this.customers = customers;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * DRAFT to CONFIRMED: reserves every item (or takes it, when the business takes stock at
     * confirmation). A short item refuses the whole order with 409 {@code stock.insufficient}.
     */
    @Transactional
    OrderViews.Detail confirm(UUID id, Long version) {
        CurrentBusiness me = CurrentBusiness.require();
        Order order = locked(id, version, Set.of(OrderStatus.DRAFT));
        OrderService.requireDeliveryDetails(order.parties());
        Instant now = clock.instant();
        StockState taken = stock.take(id, service.currentLines(id), settings.deductsStockAtConfirm());
        order.confirm(taken, now);
        customers.orderConfirmed(order.parties().customerId(), now);
        orders.flush();
        events.record(id, OrderStatus.DRAFT, OrderStatus.CONFIRMED, me.userId(), "", now);
        audit.record(me.businessId(), me.userId(), "order.confirmed", "order", id, "number", order.getNumberLabel());
        return service.detail(order);
    }

    /** CONFIRMED to PACKED. Its items are fixed from here (no more edits). */
    @Transactional
    OrderViews.Detail pack(UUID id, Long version) {
        CurrentBusiness me = CurrentBusiness.require();
        Order order = locked(id, version, Set.of(OrderStatus.CONFIRMED));
        Instant now = clock.instant();
        order.pack(now);
        orders.flush();
        events.record(id, OrderStatus.CONFIRMED, OrderStatus.PACKED, me.userId(), "", now);
        audit.record(me.businessId(), me.userId(), "order.packed", "order", id, "number", order.getNumberLabel());
        return service.detail(order);
    }

    /** What the courier took: name, tracking number (may be empty) and its charge to the business, in poisha. */
    record Handover(String courier, String trackingNo, long courierCharge) {
    }

    /**
     * PACKED (or CONFIRMED: packing is optional) to SHIPPED, with a shipment. Reserved stock is
     * taken now (SHIP movements); stock taken at confirmation stays as it is. The courier is to
     * collect the order's COD amount.
     */
    @Transactional
    OrderViews.Detail ship(UUID id, Handover handover, Long version) {
        CurrentBusiness me = CurrentBusiness.require();
        String courier = Text.cleanName(handover.courier(), "courier", MAX_COURIER);
        String tracking = handover.trackingNo() == null ? "" : handover.trackingNo().strip();
        if (tracking.length() > MAX_TRACKING) {
            throw ApiException.invalidField("trackingNo", "field.too_long");
        }
        if (handover.courierCharge() < 0 || handover.courierCharge() > Totals.MAX_TOTAL) {
            throw ApiException.invalidField("courierCharge", "field.invalid_value");
        }
        Order order = locked(id, version, Set.of(OrderStatus.CONFIRMED, OrderStatus.PACKED));
        OrderStatus from = order.getStatus();
        Instant now = clock.instant();
        stock.ship(id, service.currentLines(id), order.getStockState());
        order.ship(now);
        shipments.save(new Shipment(id, courier, tracking, handover.courierCharge(), order.getCodAmount(), me.userId(), now));
        orders.flush();
        events.record(id, from, OrderStatus.SHIPPED, me.userId(), courier + (tracking.isEmpty() ? "" : " " + tracking), now);
        audit.record(me.businessId(), me.userId(), "order.shipped", "order", id, "number", order.getNumberLabel(),
                "courier", courier, "trackingNo", tracking);
        return service.detail(order);
    }

    /**
     * SHIPPED to DELIVERED: the customer has the parcel. {@code codCollected} is what the courier
     * took from them (null: what it was to collect). Less leaves the order partly paid with a
     * balance due; refused items come back through a return (8c).
     */
    @Transactional
    OrderViews.Detail deliver(UUID id, Long codCollected, Long version) {
        CurrentBusiness me = CurrentBusiness.require();
        Order order = locked(id, version, Set.of(OrderStatus.SHIPPED));
        Shipment shipment = shipments.findInTransit(id).orElseThrow();
        long collected = codCollected == null ? shipment.getCodExpected() : codCollected;
        if (collected < 0 || collected > shipment.getCodExpected()) {
            throw ApiException.invalidField("codCollected", "field.cod_collected_range");
        }
        Instant now = clock.instant();
        shipment.delivered(collected, now);
        order.deliver(collected, now);
        orders.flush();
        events.record(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED, me.userId(), "", now);
        audit.record(me.businessId(), me.userId(), "order.delivered", "order", id, "number", order.getNumberLabel(),
                "codCollected", collected);
        return service.detail(order);
    }

    /** DRAFT, CONFIRMED or PACKED to CANCELLED, with a reason; gives back whatever stock it held. */
    @Transactional
    OrderViews.Detail cancel(UUID id, String reason, Long version) {
        CurrentBusiness me = CurrentBusiness.require();
        String why = Text.requireNonBlank(reason, "reason");
        if (why.length() > MAX_REASON) {
            throw ApiException.invalidField("reason", "field.too_long");
        }
        Order order = locked(id, version, CANCELLABLE);
        OrderStatus from = order.getStatus();
        Instant now = clock.instant();
        List<Totals.Line> lines = service.currentLines(id);
        stock.giveBack(id, lines, order.getStockState());
        if (from != OrderStatus.DRAFT) {
            customers.confirmedOrderCancelled(order.parties().customerId());
        }
        order.cancel(why, now);
        orders.flush();
        events.record(id, from, OrderStatus.CANCELLED, me.userId(), why, now);
        audit.record(me.businessId(), me.userId(), "order.cancelled", "order", id, "number", order.getNumberLabel(),
                "from", from.name(), "reason", why);
        return service.detail(order);
    }

    /** The order, row locked, in one of {@code allowed}; else 409 with the status it is in. */
    private Order locked(UUID id, Long version, Set<OrderStatus> allowed) {
        Order order = orders.findLocked(id).orElseThrow(ApiException::notFound);
        if (version != null && !Objects.equals(version, order.getVersion())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (!allowed.contains(order.getStatus())) {
            throw new ApiException(ErrorCode.ORDER_INVALID_TRANSITION).withDetail("status", order.getStatus().name());
        }
        return order;
    }
}
