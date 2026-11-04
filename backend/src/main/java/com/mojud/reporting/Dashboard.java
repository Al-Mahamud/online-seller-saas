package com.mojud.reporting;

import com.mojud.catalog.CatalogReports;
import com.mojud.orders.OrderReports;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.Permission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "Today" panel (implementation-plan.md 9a), assembled from the figures orders and catalog
 * report about themselves. Money in poisha. Profit only for managers and owners.
 */
@Service
class Dashboard {

    record Confirmed(int orders, long value) {
    }

    /** Couriers' cash: still to collect on the road, collected and not yet paid out, and both. */
    record Cod(long inTransit, long withCourier, long outstanding) {
    }

    record Today(LocalDate date, Confirmed confirmed, int cancelled, Map<String, Integer> byStatus, int shipped,
            int delivered, Cod cod, int lowStock, List<OrderReports.TopProduct> topProducts, OrderReports.@Nullable Profit profit) {
    }

    /** A parcel this long on the way, or COD this long with a courier, needs chasing. */
    static final int LATE_AFTER_DAYS = 5;
    static final int STUCK_AFTER_DAYS = 7;
    static final int DELIVERY_WINDOW_DAYS = 30;

    /** What needs attention and how deliveries stand now, whichever day is on screen. */
    record Overview(int lateParcels, int lateAfterDays, List<OrderReports.CourierCash> stuckCod, int stuckAfterDays,
            int windowDays, List<OrderReports.CourierParcels> couriers, Map<String, Integer> returnReasons,
            boolean hasProducts, boolean hasOrders) {
    }

    private final OrderReports orders;
    private final ReportRange ranges;
    private final CatalogReports catalog;
    private final Clock clock;

    Dashboard(OrderReports orders, CatalogReports catalog, Clock clock, ReportRange ranges) {
        this.ranges = ranges;
        this.orders = orders;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** {@code date}: a Dhaka day, today when null; not in the future. */
    @Transactional(readOnly = true)
    Today today(LocalDate date) {
        LocalDate today = DhakaTime.today(clock);
        LocalDate day = date == null ? today : date;
        if (day.isAfter(today)) {
            throw ApiException.invalidField("date", "field.invalid_value");
        }
        ranges.requireReachable(day);
        OrderReports.Day figures = orders.day(day);
        boolean seesProfit = CurrentBusiness.require().can(Permission.COSTS);
        return new Today(day, new Confirmed(figures.confirmedOrders(), figures.confirmedValue()), figures.cancelledOrders(),
                figures.byStatus(), figures.shipped(), figures.delivered(),
                new Cod(figures.codInTransit(), figures.codWithCourier(), figures.codInTransit() + figures.codWithCourier()),
                catalog.lowStockCount(), figures.topProducts(), seesProfit ? figures.profit() : null);
    }

    @Transactional(readOnly = true)
    Overview overview() {
        Instant now = clock.instant();
        Instant shippedSince = DhakaTime.startOf(DhakaTime.today(clock).minusDays(DELIVERY_WINDOW_DAYS - 1));
        OrderReports.Deliveries deliveries = orders.deliveries(now.minus(Duration.ofDays(LATE_AFTER_DAYS)),
                now.minus(Duration.ofDays(STUCK_AFTER_DAYS)), shippedSince);
        return new Overview(deliveries.lateParcels(), LATE_AFTER_DAYS, deliveries.stuckCod(), STUCK_AFTER_DAYS,
                DELIVERY_WINDOW_DAYS, deliveries.couriers(), deliveries.returnReasons(), catalog.hasProducts(),
                deliveries.hasOrders());
    }
}
