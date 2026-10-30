package com.mojud.orders;

import com.mojud.shared.idempotency.Idempotency;
import com.mojud.shared.paging.CursorPage;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.mojud.shared.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Orders: every member takes and edits them. Money in poisha; totals sent by clients are ignored. */
@RestController
@RequiresRole(BusinessRole.STAFF)
class OrderController {

    /** Couriers suggested before any are used; the business's own come first once it has shipped. */
    static final List<String> KNOWN_COURIERS = List.of("Pathao", "Steadfast", "RedX", "Paperfly", "eCourier",
            "Sundarban Courier", "SA Paribahan");

    private final OrderService orders;
    private final OrderTransitions transitions;
    private final BulkActions bulk;
    private final OrderPayments payments;
    private final ShipmentRepository shipments;

    OrderController(OrderService orders, OrderTransitions transitions, BulkActions bulk, OrderPayments payments,
            ShipmentRepository shipments) {
        this.orders = orders;
        this.transitions = transitions;
        this.bulk = bulk;
        this.payments = payments;
        this.shipments = shipments;
    }

    record ItemRequest(@NotNull UUID variantId, @NotNull @Min(1) @Max(10_000) Integer quantity, Long unitPrice) {
    }

    record CustomerRequest(@NotNull @Size(max = 200) String name, @NotNull @Size(max = 40) String phone,
            @Size(max = 1000) String address, Integer districtId, Integer areaId) {
    }

    record DeliveryRequest(@Size(max = 200) String name, @Size(max = 40) String phone, @Size(max = 1000) String address,
            Integer districtId, Integer areaId) {
    }

    record CreateRequest(UUID customerId, @Valid CustomerRequest customer, @Valid DeliveryRequest delivery,
            @NotNull @Size(max = 200) List<@Valid @NotNull ItemRequest> items, Long discount, Long deliveryCharge,
            Long advancePaid, PaymentMethod advanceMethod, @Size(max = 200) String advanceReference,
            @Size(max = 2000) String note) {
    }

    /** Creates a DRAFT order. With an {@code Idempotency-Key} header, a resend returns the first answer. */
    @PostMapping("/api/v1/orders")
    @ResponseStatus(HttpStatus.CREATED)
    OrderViews.Detail create(@Valid @RequestBody CreateRequest body,
            @RequestHeader(name = Idempotency.HEADER, required = false) @Size(max = 100) String idempotencyKey) {
        return orders.create(new OrderService.Draft(body.customerId(), customer(body.customer()), delivery(body.delivery()),
                items(body.items()), body.discount(), body.deliveryCharge(), body.advancePaid(), body.advanceMethod(),
                body.advanceReference(), body.note()), idempotencyKey);
    }

    @GetMapping("/api/v1/orders")
    CursorPage<OrderViews.Summary> list(@RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String phone, @RequestParam(required = false) String number,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return orders.list(status, from, to, phone, number, cursor, limit);
    }

    /** How many orders each status has, for the same filters as the list: the tabs' numbers. */
    @GetMapping("/api/v1/orders/counts")
    Map<OrderStatus, Integer> counts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String phone, @RequestParam(required = false) String number) {
        return orders.counts(from, to, phone, number);
    }

    @GetMapping("/api/v1/orders/{id}")
    OrderViews.Detail get(@PathVariable UUID id) {
        return orders.get(id);
    }

    /** {@code advancePaid} is not accepted here: record a payment instead (POST /orders/{id}/payments). */
    record UpdateRequest(UUID customerId, @Valid CustomerRequest customer, @Valid DeliveryRequest delivery,
            @Size(max = 200) List<@Valid @NotNull ItemRequest> items, Long discount, Long deliveryCharge, Long advancePaid,
            @Size(max = 2000) String note, Long version) {
    }

    /** Edits a DRAFT or CONFIRMED order (a confirmed one moves only the stock difference); fields left out stay. */
    @PatchMapping("/api/v1/orders/{id}")
    OrderViews.Detail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        if (body.advancePaid() != null) {
            throw ApiException.invalidField("advancePaid", "field.advance_use_payments");
        }
        return orders.update(id, new OrderService.Changes(body.customerId(), customer(body.customer()),
                delivery(body.delivery()), body.items() == null ? null : items(body.items()), body.discount(),
                body.deliveryCharge(), body.note(), body.version()));
    }

    record ConfirmRequest(Long version) {
    }

    /** Reserves (or takes) the items' stock; 409 {@code stock.insufficient} names the short items. */
    @PostMapping("/api/v1/orders/{id}/confirm")
    OrderViews.Detail confirm(@PathVariable UUID id, @RequestBody(required = false) ConfirmRequest body) {
        return transitions.confirm(id, body == null ? null : body.version());
    }

    @PostMapping("/api/v1/orders/{id}/pack")
    OrderViews.Detail pack(@PathVariable UUID id, @RequestBody(required = false) ConfirmRequest body) {
        return transitions.pack(id, body == null ? null : body.version());
    }

    record ShipRequest(@NotNull @Size(max = 200) String courier, @Size(max = 200) String trackingNo, Long courierCharge,
            Long version) {
    }

    /** From PACKED, or CONFIRMED (packing is optional). {@code courierCharge}: what the courier charges the business. */
    @PostMapping("/api/v1/orders/{id}/ship")
    OrderViews.Detail ship(@PathVariable UUID id, @Valid @RequestBody ShipRequest body) {
        return transitions.ship(id, new OrderTransitions.Handover(body.courier(), body.trackingNo(),
                body.courierCharge() == null ? 0 : body.courierCharge()), body.version());
    }

    record ReturnLineRequest(@NotNull UUID orderItemId, @NotNull @Min(1) @Max(10_000) Integer quantity,
            @NotNull OrderReturns.Disposition disposition) {
    }

    /**
     * {@code codCollected}: what the courier took from the customer (left out: what it was to
     * collect, less what refused items took off). {@code refused}: items the customer turned down
     * at the door, each restocked or written off.
     */
    record DeliverRequest(Long codCollected, @Size(max = 200) List<@Valid @NotNull ReturnLineRequest> refused,
            @Size(max = 1000) String reason, Long version) {
    }

    @PostMapping("/api/v1/orders/{id}/deliver")
    OrderViews.Detail deliver(@PathVariable UUID id, @Valid @RequestBody(required = false) DeliverRequest body) {
        return body == null ? transitions.deliver(id, null, null, null, null)
                : transitions.deliver(id, body.codCollected(), lines(body.refused()), body.reason(), body.version());
    }

    record RefundRequest(@NotNull Long amount, @NotNull PaymentMethod method, @Size(max = 200) String reference) {
    }

    /**
     * {@code kind}: FAILED_DELIVERY (a shipped parcel came back; items left out all go back on the
     * shelf) or CUSTOMER_RETURN (some or all of a delivered order).
     */
    record ReturnRequest(@NotNull ReturnKind kind, @NotNull @Size(max = 1000) String reason,
            @Size(max = 200) List<@Valid @NotNull ReturnLineRequest> items, @Valid RefundRequest refund, Long version) {
    }

    @PostMapping("/api/v1/orders/{id}/returns")
    @ResponseStatus(HttpStatus.CREATED)
    OrderViews.Detail returnGoods(@PathVariable UUID id, @Valid @RequestBody ReturnRequest body) {
        OrderReturns.Refund refund = body.refund() == null ? null : new OrderReturns.Refund(body.refund().amount(),
                body.refund().method(), OrderService.reference(body.refund().reference()));
        return transitions.returnGoods(id, body.kind(), body.reason(), lines(body.items()), refund, body.version());
    }

    private static List<OrderReturns.Line> lines(List<ReturnLineRequest> requests) {
        return requests == null ? null : requests.stream()
                .map(line -> new OrderReturns.Line(line.orderItemId(), line.quantity(), line.disposition())).toList();
    }

    record PaymentRequest(@NotNull PaymentMethod method, @NotNull Long amount, @Size(max = 200) String reference,
            Instant receivedAt) {
    }

    /**
     * Money paid to the business directly: before shipping (an advance) or after delivery (a shortfall).
     * With an {@code Idempotency-Key} header, a resend returns the first answer and records nothing more.
     */
    @PostMapping("/api/v1/orders/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    OrderViews.Detail pay(@PathVariable UUID id, @Valid @RequestBody PaymentRequest body,
            @RequestHeader(name = Idempotency.HEADER, required = false) @Size(max = 100) String idempotencyKey) {
        return payments.record(id, body.method(), body.amount(), body.reference(), body.receivedAt(), idempotencyKey);
    }

    record BulkPackRequest(@NotNull @Size(max = 200) List<@NotNull UUID> orderIds) {
    }

    /** Each order separately; the answer lists how each went, in the order given. */
    @PostMapping("/api/v1/orders/bulk/pack")
    List<BulkActions.Result> packMany(@Valid @RequestBody BulkPackRequest body) {
        return bulk.pack(body.orderIds());
    }

    record BulkShipLine(@NotNull UUID orderId, @Size(max = 200) String trackingNo, Long courierCharge) {
    }

    record BulkShipRequest(@NotNull @Size(max = 200) String courier, Long courierCharge,
            @NotNull @Size(max = 200) List<@Valid @NotNull BulkShipLine> orders) {
    }

    /** One courier for all; each order separately, with its own tracking number. */
    @PostMapping("/api/v1/orders/bulk/ship")
    List<BulkActions.Result> shipMany(@Valid @RequestBody BulkShipRequest body) {
        return bulk.ship(body.courier(), body.courierCharge(), body.orders().stream()
                .map(line -> new BulkActions.ShipLine(line.orderId(), line.trackingNo(), line.courierCharge())).toList());
    }

    /** Courier names for the ship form: the business's own, most used first, then well-known ones. */
    @GetMapping("/api/v1/shipments/couriers")
    @Transactional(readOnly = true)
    List<String> couriers() {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        shipments.findCouriers().forEach(name -> names.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
        KNOWN_COURIERS.forEach(name -> names.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
        return List.copyOf(names.values());
    }

    record CancelRequest(@NotNull @Size(max = 1000) String reason, Long version) {
    }

    /** From DRAFT, CONFIRMED or PACKED, with a reason; gives back the stock it held. */
    @PostMapping("/api/v1/orders/{id}/cancel")
    OrderViews.Detail cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest body) {
        return transitions.cancel(id, body.reason(), body.version());
    }

    private static OrderService.CustomerInput customer(CustomerRequest request) {
        return request == null ? null : new OrderService.CustomerInput(request.name(), request.phone(), request.address(),
                request.districtId(), request.areaId());
    }

    private static OrderService.DeliveryInput delivery(DeliveryRequest request) {
        return request == null ? null : new OrderService.DeliveryInput(request.name(), request.phone(), request.address(),
                request.districtId(), request.areaId());
    }

    private static List<OrderService.ItemInput> items(List<ItemRequest> requests) {
        return requests.stream().map(item -> new OrderService.ItemInput(item.variantId(), item.quantity(), item.unitPrice()))
                .toList();
    }
}
