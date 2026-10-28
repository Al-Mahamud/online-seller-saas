package com.mojud.reporting;

import com.mojud.catalog.CatalogReports;
import com.mojud.orders.OrderReports;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
            int delivered, Cod cod, int lowStock, List<OrderReports.TopProduct> topProducts, OrderReports.Profit profit) {
    }

    private final OrderReports orders;
    private final CatalogReports catalog;
    private final Clock clock;

    Dashboard(OrderReports orders, CatalogReports catalog, Clock clock) {
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
        OrderReports.Day figures = orders.day(day);
        boolean seesProfit = CurrentBusiness.require().isAtLeast(BusinessRole.MANAGER);
        return new Today(day, new Confirmed(figures.confirmedOrders(), figures.confirmedValue()), figures.cancelledOrders(),
                figures.byStatus(), figures.shipped(), figures.delivered(),
                new Cod(figures.codInTransit(), figures.codWithCourier(), figures.codInTransit() + figures.codWithCourier()),
                catalog.lowStockCount(), figures.topProducts(), seesProfit ? figures.profit() : null);
    }
}
