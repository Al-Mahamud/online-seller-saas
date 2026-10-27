package com.mojud.orders;

import com.mojud.shared.error.ApiException;
import java.util.List;
import java.util.UUID;

/**
 * An order's money (backend-plan.md §5.3), in poisha, from its lines and three amounts the staff
 * give: {@code total = subtotal − discount + deliveryCharge}, {@code codAmount = total − advancePaid}.
 * Whatever totals a client sends are ignored. The database checks the same arithmetic.
 */
record Totals(long subtotal, long discount, long deliveryCharge, long total, long advancePaid, long codAmount) {

    /** ৳1,000,000,000: far above any real order; keeps every sum far from overflowing. */
    static final long MAX_TOTAL = 100_000_000_000L;

    /** A priced line, with the catalog's names copied in. */
    record Line(UUID variantId, String productName, String variantLabel, String sku, int quantity, long unitPrice,
            Long unitCost) {

        long amount() {
            return Math.multiplyExact((long) quantity, unitPrice);
        }
    }

    static Totals of(List<Line> lines, long discount, long deliveryCharge, long advancePaid) {
        long subtotal = 0;
        for (Line line : lines) {
            subtotal = Math.addExact(subtotal, line.amount());
            if (subtotal > MAX_TOTAL) {
                throw ApiException.invalidField("items", "field.order_too_large");
            }
        }
        if (discount > subtotal) {
            throw ApiException.invalidField("discount", "field.discount_too_large");
        }
        long total = subtotal - discount + deliveryCharge;
        if (total > MAX_TOTAL) {
            throw ApiException.invalidField("deliveryCharge", "field.order_too_large");
        }
        if (advancePaid > total) {
            throw ApiException.invalidField("advancePaid", "field.advance_too_large");
        }
        return new Totals(subtotal, discount, deliveryCharge, total, advancePaid, total - advancePaid);
    }
}
