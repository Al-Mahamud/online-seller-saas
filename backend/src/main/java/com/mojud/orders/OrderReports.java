package com.mojud.orders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** A courier's parcels: on the way now, and of those shipped in the window, how they ended. */
    record CourierParcels(String courier, int onTheWay, int delivered, int returned) {
    }

    /** COD a courier collected and hasn't paid out, delivered too long ago. */
    record CourierCash(String courier, long amount) {
    }

    /**
     * How deliveries stand now, not tied to a day. {@code lateParcels}: on the way, shipped before
     * {@code lateBefore}. {@code couriers} and {@code returnReasons} (by return kind) count parcels
     * shipped since {@code shippedSince}, so the reasons belong to the same parcels.
     */
    record Deliveries(int lateParcels, List<CourierCash> stuckCod, List<CourierParcels> couriers,
            Map<String, Integer> returnReasons, boolean hasOrders) {
    }

    Deliveries deliveries(Instant lateBefore, Instant stuckBefore, Instant shippedSince);

    /**
     * Sales of one Dhaka day: orders confirmed that day and not cancelled. {@code quantity}: items
     * kept (ordered less returned); {@code revenue}: goods after discount and returns;
     * {@code deliveryCharged}: delivery charges customers still owe (none on a failed delivery).
     */
    record DaySales(LocalDate day, int orders, int quantity, long revenue, long deliveryCharged) {
    }

    /** One variant's sales over a range: items kept, at their order prices (before order discounts). */
    record VariantSales(UUID variantId, String productName, String variantLabel, int quantity, long value) {
    }

    /**
     * Profit over a range of Dhaka days (orders confirmed then, not cancelled). Goods: revenue less
     * the cost of goods kept and of goods written off after coming back. Delivery: what customers
     * pay for it less what couriers charged (every shipment, failed ones too).
     */
    record ProfitReport(int orders, long revenue, long costOfGoods, long writeOffs, long grossProfit, long deliveryCharged,
            long courierCharges, long deliveryMargin, long netProfit, int itemsWithoutCost) {
    }

    /** Days in order, each day of the range (days without sales are zero). */
    List<DaySales> salesByDay(LocalDate from, LocalDate to);

    List<VariantSales> salesByVariant(LocalDate from, LocalDate to);

    ProfitReport profit(LocalDate from, LocalDate to);
}
