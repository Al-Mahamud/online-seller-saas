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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
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
            Long advancePaid, @Size(max = 2000) String note) {
    }

    /** Creates a DRAFT order. With an {@code Idempotency-Key} header, a resend returns the first answer. */
    @PostMapping("/api/v1/orders")
    @ResponseStatus(HttpStatus.CREATED)
    OrderViews.Detail create(@Valid @RequestBody CreateRequest body,
            @RequestHeader(name = Idempotency.HEADER, required = false) @Size(max = 100) String idempotencyKey) {
        return orders.create(new OrderService.Draft(body.customerId(), customer(body.customer()), delivery(body.delivery()),
                items(body.items()), body.discount(), body.deliveryCharge(), body.advancePaid(), body.note()), idempotencyKey);
    }

    @GetMapping("/api/v1/orders")
    CursorPage<OrderViews.Summary> list(@RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String phone, @RequestParam(required = false) String number,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return orders.list(status, from, to, phone, number, cursor, limit);
    }

    @GetMapping("/api/v1/orders/{id}")
    OrderViews.Detail get(@PathVariable UUID id) {
        return orders.get(id);
    }

    record UpdateRequest(UUID customerId, @Valid CustomerRequest customer, @Valid DeliveryRequest delivery,
            @Size(max = 200) List<@Valid @NotNull ItemRequest> items, Long discount, Long deliveryCharge, Long advancePaid,
            @Size(max = 2000) String note, Long version) {
    }

    /** Edits a DRAFT order; fields left out stay. */
    @PatchMapping("/api/v1/orders/{id}")
    OrderViews.Detail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        return orders.update(id, new OrderService.Changes(body.customerId(), customer(body.customer()),
                delivery(body.delivery()), body.items() == null ? null : items(body.items()), body.discount(),
                body.deliveryCharge(), body.advancePaid(), body.note(), body.version()));
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
