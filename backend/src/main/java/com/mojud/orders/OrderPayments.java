package com.mojud.orders;

import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.idempotency.Idempotency;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments the customer makes to the business directly (implementation-plan.md 8b). Before the
 * parcel leaves, a payment is an advance and lowers what the courier will collect; after delivery
 * it pays off a shortfall. Not while the courier has the parcel: its COD amount is already fixed.
 */
@Service
class OrderPayments {

    private static final Set<OrderStatus> PAYABLE = Set.of(OrderStatus.DRAFT, OrderStatus.CONFIRMED, OrderStatus.PACKED,
            OrderStatus.DELIVERED);

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final OrderService service;
    private final Idempotency idempotency;
    private final AuditTrail audit;
    private final Clock clock;

    OrderPayments(OrderRepository orders, PaymentRepository payments, OrderService service, Idempotency idempotency,
            AuditTrail audit, Clock clock) {
        this.idempotency = idempotency;
        this.orders = orders;
        this.payments = payments;
        this.service = service;
        this.audit = audit;
        this.clock = clock;
    }

    /** What a resend must repeat to be the same payment. */
    private record Request(UUID orderId, PaymentMethod method, long amount, String reference, Instant receivedAt) {
    }

    /**
     * @param receivedAt when the money came (null: now); not in the future
     * @param idempotencyKey a resend with the same key returns the first answer and records nothing more
     */
    @Transactional
    OrderViews.Detail record(UUID orderId, PaymentMethod method, long amount, String reference, Instant receivedAt,
            String idempotencyKey) {
        return idempotency.run(idempotencyKey, new Request(orderId, method, amount, reference, receivedAt),
                OrderViews.Detail.class, () -> recordOnce(orderId, method, amount, reference, receivedAt));
    }

    private OrderViews.Detail recordOnce(UUID orderId, PaymentMethod method, long amount, String reference, Instant receivedAt) {
        CurrentBusiness me = CurrentBusiness.require();
        String ref = OrderService.reference(reference);
        Instant now = clock.instant();
        if (receivedAt != null && receivedAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.invalidField("receivedAt", "field.invalid_value");
        }
        Order order = orders.findLocked(orderId).orElseThrow(ApiException::notFound);
        if (!PAYABLE.contains(order.getStatus())) {
            throw new ApiException(ErrorCode.ORDER_NOT_PAYABLE).withDetail("status", order.getStatus().name());
        }
        if (amount <= 0) {
            throw ApiException.invalidField("amount", "field.invalid_value");
        }
        if (amount > order.balanceDue()) {
            throw ApiException.invalidField("amount", "field.payment_too_large");
        }
        order.paid(amount, now);
        payments.save(new Payment(orderId, method, amount, ref, receivedAt == null ? now : receivedAt, me.userId(), now));
        orders.flush();
        audit.record(me.businessId(), me.userId(), "payment.recorded", "order", orderId, "number", order.getNumberLabel(),
                "method", method.name(), "amount", amount);
        return service.detail(order);
    }
}
