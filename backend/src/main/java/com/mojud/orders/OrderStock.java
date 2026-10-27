package com.mojud.orders;

import com.mojud.inventory.StockService;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * An order's stock moves (backend-plan.md §6), in the order's transaction and after its row is
 * locked. Shortages name the order's items, not just variant ids.
 */
@Component
class OrderStock {

    private final StockService stock;

    OrderStock(StockService stock) {
        this.stock = stock;
    }

    /** Confirmation: reserve (stock taken at shipping) or take (at confirmation). */
    @Transactional(propagation = Propagation.MANDATORY)
    StockState take(UUID orderId, List<Totals.Line> lines, boolean deduct) {
        StockService.Reference reference = StockService.Reference.order(orderId);
        List<StockService.Line> stockLines = lines.stream().map(l -> new StockService.Line(l.variantId(), l.quantity())).toList();
        named(lines, () -> {
            if (deduct) {
                stock.sell(stockLines, reference);
            } else {
                stock.reserve(stockLines, reference);
            }
        });
        return deduct ? StockState.DEDUCTED : StockState.RESERVED;
    }

    /** Shipping: a reservation becomes a deduction (SHIP); stock already taken (DEDUCTED) stays as it is. */
    @Transactional(propagation = Propagation.MANDATORY)
    void ship(UUID orderId, List<Totals.Line> lines, StockState state) {
        if (state == StockState.RESERVED) {
            stock.ship(lines.stream().map(l -> new StockService.Line(l.variantId(), l.quantity())).toList(),
                    StockService.Reference.order(orderId));
        }
    }

    /** Cancellation: undoes what the order holds. */
    @Transactional(propagation = Propagation.MANDATORY)
    void giveBack(UUID orderId, List<Totals.Line> lines, StockState state) {
        if (state == StockState.NONE || lines.isEmpty()) {
            return;
        }
        List<StockService.Line> stockLines = lines.stream().map(l -> new StockService.Line(l.variantId(), l.quantity())).toList();
        if (state == StockState.RESERVED) {
            stock.release(stockLines, StockService.Reference.order(orderId));
        } else {
            stock.unsell(stockLines, StockService.Reference.order(orderId));
        }
    }

    /**
     * An edit of a confirmed order: only the difference moves, more taken for what grew and given
     * back for what shrank. Every variant involved is locked first, in one order (no deadlocks).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void rebalance(UUID orderId, List<Totals.Line> before, List<Totals.Line> after, StockState state) {
        if (state == StockState.NONE) {
            return;
        }
        Map<UUID, Integer> change = new LinkedHashMap<>();
        after.forEach(line -> change.merge(line.variantId(), line.quantity(), Integer::sum));
        before.forEach(line -> change.merge(line.variantId(), -line.quantity(), Integer::sum));
        List<StockService.Line> more = new ArrayList<>();
        List<StockService.Line> less = new ArrayList<>();
        change.forEach((variant, delta) -> {
            if (delta > 0) {
                more.add(new StockService.Line(variant, delta));
            } else if (delta < 0) {
                less.add(new StockService.Line(variant, -delta));
            }
        });
        Set<UUID> involved = new HashSet<>();
        more.forEach(line -> involved.add(line.variantId()));
        less.forEach(line -> involved.add(line.variantId()));
        stock.lockAll(involved);
        StockService.Reference reference = StockService.Reference.order(orderId);
        if (!less.isEmpty()) {
            if (state == StockState.RESERVED) {
                stock.release(less, reference);
            } else {
                stock.unsell(less, reference);
            }
        }
        if (!more.isEmpty()) {
            named(after, () -> {
                if (state == StockState.RESERVED) {
                    stock.reserve(more, reference);
                } else {
                    stock.sell(more, reference);
                }
            });
        }
    }

    /** Adds each short item's product name, option label and SKU to the shortages. */
    @SuppressWarnings("unchecked")
    private static void named(List<Totals.Line> lines, Runnable work) {
        try {
            work.run();
        } catch (ApiException e) {
            if (e.code() != ErrorCode.STOCK_INSUFFICIENT || !(e.details().get("shortages") instanceof List<?> shortages)) {
                throw e;
            }
            Map<UUID, Totals.Line> byVariant = new LinkedHashMap<>();
            lines.forEach(line -> byVariant.put(line.variantId(), line));
            List<Map<String, Object>> named = new ArrayList<>();
            for (Object entry : shortages) {
                Map<String, Object> shortage = new LinkedHashMap<>((Map<String, Object>) entry);
                Totals.Line line = byVariant.get((UUID) shortage.get("variantId"));
                if (line != null) {
                    shortage.put("productName", line.productName());
                    shortage.put("variantLabel", line.variantLabel());
                    shortage.put("sku", line.sku());
                }
                named.add(shortage);
            }
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT).withDetail("shortages", named);
        }
    }
}
