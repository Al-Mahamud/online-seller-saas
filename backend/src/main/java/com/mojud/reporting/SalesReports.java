package com.mojud.reporting;

import com.mojud.catalog.CatalogReports;
import com.mojud.orders.OrderReports;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales and profit over a range of Dhaka days (implementation-plan.md 9b), from what orders and
 * catalog report. Orders count from the day they were confirmed, unless cancelled; goods count as
 * kept (ordered less returned). Money in poisha.
 */
@Service
class SalesReports {

    enum GroupBy {
        DAY, PRODUCT, CATEGORY
    }

    /**
     * {@code value}: for days, goods after discount and returns (revenue); for products and
     * categories, items kept at their order prices (order discounts are not split over items).
     * {@code orders} and {@code deliveryCharged} only for days.
     */
    record Row(String key, String label, @Nullable Integer orders, int quantity, long value, @Nullable Long deliveryCharged) {
    }

    record Sales(LocalDate from, LocalDate to, GroupBy groupBy, List<Row> rows, int quantity, long value) {
    }

    record Profit(LocalDate from, LocalDate to, OrderReports.ProfitReport profit) {
    }

    private final OrderReports orders;
    private final CatalogReports catalog;
    private final MessageSource messages;
    private final ReportRange ranges;

    SalesReports(OrderReports orders, CatalogReports catalog, MessageSource messages, ReportRange ranges) {
        this.orders = orders;
        this.catalog = catalog;
        this.messages = messages;
        this.ranges = ranges;
    }

    @Transactional(readOnly = true)
    Sales sales(LocalDate from, LocalDate to, GroupBy groupBy) {
        ReportRange.Range range = ranges.resolve(from, to);
        List<Row> rows = switch (groupBy) {
            case DAY -> orders.salesByDay(range.from(), range.to()).stream()
                    .map(day -> new Row(day.day().toString(), day.day().toString(), day.orders(), day.quantity(),
                            day.revenue(), day.deliveryCharged()))
                    .toList();
            case PRODUCT -> grouped(orders.salesByVariant(range.from(), range.to()), sale -> sale.productName());
            case CATEGORY -> {
                List<OrderReports.VariantSales> sales = orders.salesByVariant(range.from(), range.to());
                Map<UUID, String> paths = catalog.categoryPaths(sales.stream()
                        .map(OrderReports.VariantSales::variantId).toList());
                String none = messages.getMessage("report.uncategorised", null, "Uncategorised", LocaleContextHolder.getLocale());
                yield grouped(sales, sale -> Objects.requireNonNullElse(paths.get(sale.variantId()), none));
            }
        };
        return new Sales(range.from(), range.to(), groupBy, rows, rows.stream().mapToInt(Row::quantity).sum(),
                rows.stream().mapToLong(Row::value).sum());
    }

    @Transactional(readOnly = true)
    Profit profit(LocalDate from, LocalDate to) {
        ReportRange.Range range = ranges.resolve(from, to);
        return new Profit(range.from(), range.to(), orders.profit(range.from(), range.to()));
    }

    /** Most sold first. */
    private static List<Row> grouped(List<OrderReports.VariantSales> sales,
            Function<OrderReports.VariantSales, String> key) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (OrderReports.VariantSales sale : sales) {
            long[] sum = totals.computeIfAbsent(key.apply(sale), k -> new long[2]);
            sum[0] += sale.quantity();
            sum[1] += sale.value();
        }
        return totals.entrySet().stream()
                .map(e -> new Row(e.getKey(), e.getKey(), null, (int) e.getValue()[0], e.getValue()[1], null))
                .sorted(Comparator.comparingLong(Row::value).reversed().thenComparing(Row::label))
                .toList();
    }
}
