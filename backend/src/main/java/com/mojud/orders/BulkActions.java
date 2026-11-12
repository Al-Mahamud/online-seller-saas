package com.mojud.orders;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

/**
 * Packing or shipping many orders at once (implementation-plan.md 8a). Each order is its own
 * transaction, so one that cannot move (cancelled meanwhile, edited by someone else) does not hold
 * back the others; the answer says how each one went. Deliberately not transactional itself.
 */
@Service
class BulkActions {

    private static final Logger log = LoggerFactory.getLogger(BulkActions.class);
    static final int MAX_ORDERS = 100;

    /** {@code ok}: moved; otherwise {@code code} and {@code message} say why not. */
    record Result(UUID orderId, boolean ok, @Nullable String status, @Nullable String numberLabel, @Nullable String code,
            @Nullable String message) {
    }

    record ShipLine(UUID orderId, String trackingNo, Long courierCharge) {
    }

    private final OrderTransitions transitions;
    private final MessageSource messages;

    BulkActions(OrderTransitions transitions, MessageSource messages) {
        this.transitions = transitions;
        this.messages = messages;
    }

    List<Result> pack(List<UUID> orderIds) {
        checkSize(orderIds.size());
        List<Result> results = new ArrayList<>();
        for (UUID id : orderIds) {
            results.add(each(id, () -> transitions.pack(id, null)));
        }
        return results;
    }

    /** One courier for all; each order its own tracking number, and its own charge or the common one. */
    List<Result> ship(String courier, Long courierCharge, List<ShipLine> lines) {
        checkSize(lines.size());
        List<Result> results = new ArrayList<>();
        for (ShipLine line : lines) {
            long charge = line.courierCharge() != null ? line.courierCharge() : courierCharge == null ? 0 : courierCharge;
            results.add(each(line.orderId(), () -> transitions.ship(line.orderId(),
                    new OrderTransitions.Handover(courier, line.trackingNo(), charge), null)));
        }
        return results;
    }

    private Result each(UUID id, Supplier<OrderViews.Detail> work) {
        try {
            OrderViews.Detail order = work.get();
            return new Result(id, true, order.status().name(), order.numberLabel(), null, null);
        } catch (ApiException e) {
            Locale locale = LocaleContextHolder.getLocale();
            String message = e.code() == ErrorCode.VALIDATION_FAILED
                    ? messages.getMessage("error." + e.code().code(), null, e.code().code(), locale)
                    : messages.getMessage("error." + e.code().code(), e.messageArgs(), e.code().code(), locale);
            return new Result(id, false, null, null, e.code().code(), message);
        } catch (OptimisticLockingFailureException e) {
            return failed(id, ErrorCode.CONCURRENT_MODIFICATION);
        } catch (RuntimeException e) {
            log.error("[BULK-ACTION] order {} failed", id, e);
            return failed(id, ErrorCode.SERVER_ERROR);
        }
    }

    private Result failed(UUID id, ErrorCode code) {
        return new Result(id, false, null, null, code.code(),
                messages.getMessage("error." + code.code(), null, code.code(), LocaleContextHolder.getLocale()));
    }

    private static void checkSize(int size) {
        if (size == 0 || size > MAX_ORDERS) {
            throw ApiException.invalidField("orderIds", "field.bulk_count");
        }
    }
}
