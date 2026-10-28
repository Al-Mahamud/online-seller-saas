package com.mojud.orders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Report figures from orders (implementation-plan.md 9a), for the reporting module, in the
 * business of the TenantScope. Money in poisha.
 */
public interface OrderReports {

    /** Goods sold after discount and returns, their cost, and the difference. */
    record Profit(long revenue, long cost, long grossProfit, boolean costMissing) {
    }

    record TopProduct(String productName, int quantity, long value) {
    }

    /**
     * One Dhaka day. Confirmed: orders confirmed that day and not cancelled, valued at what the
     * customer owes after returns. byStatus: open orders (DRAFT to SHIPPED) however old, finished
     * ones (DELIVERED, RETURNED, CANCELLED) that finished that day. COD: where couriers' cash is
     * now (not tied to the day).
     */
    record Day(LocalDate date, int confirmedOrders, long confirmedValue, int cancelledOrders,
            Map<String, Integer> byStatus, int shipped, int delivered, long codInTransit, long codWithCourier,
            List<TopProduct> topProducts, Profit profit) {
    }

    Day day(LocalDate date);
}
