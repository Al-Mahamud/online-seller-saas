package com.mojud.orders;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.time.DhakaTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link OrderReports} in SQL. Every query names the business (row-level security also applies). */
@Component
class JdbcOrderReports implements OrderReports {

    static final int TOP_PRODUCTS = 5;

    /** Orders confirmed in [from, to) and not cancelled. */
    private static final String DAY_ORDERS = """
            day_orders as (
                select id, total, subtotal, discount, delivery_charge, return_credit, confirmed_at from orders
                where business_id = :business and confirmed_at >= :from and confirmed_at < :to and status <> 'CANCELLED'
            ),
            returned as (
                select ri.order_item_id,
                       sum(ri.quantity) as quantity,
                       sum(ri.quantity) filter (where ri.disposition = 'RESTOCK') as restocked
                from return_items ri join returns r on r.id = ri.return_id
                where r.business_id = :business and r.order_id in (select id from day_orders)
                group by ri.order_item_id
            )
            """;

    private final JdbcClient jdbc;

    JdbcOrderReports(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    @Transactional(readOnly = true)
    public Day day(LocalDate date) {
        UUID business = TenantScope.current().orElseThrow();
        Timestamp from = Timestamp.from(DhakaTime.startOf(date));
        Timestamp to = Timestamp.from(DhakaTime.startOf(date.plusDays(1)));

        record Totals(int orders, long value, long revenue, long cost, boolean costMissing) {
        }
        Totals totals = jdbc.sql("with " + DAY_ORDERS + """
                        select (select count(*) from day_orders),
                               (select coalesce(sum(total - return_credit), 0) from day_orders),
                               -- Goods after discount, less what returns took off them (not the delivery charge).
                               (select coalesce(sum((subtotal - discount) - least(return_credit, subtotal - discount)), 0)
                                from day_orders),
                               -- Restocked goods cost nothing; written-off ones are a loss and keep their cost.
                               (select coalesce(sum((i.quantity - coalesce(r.restocked, 0)) * i.unit_cost), 0)
                                from order_items i left join returned r on r.order_item_id = i.id
                                where i.business_id = :business and i.order_id in (select id from day_orders)
                                  and i.unit_cost is not null),
                               (select count(*) > 0 from order_items i
                                where i.business_id = :business and i.order_id in (select id from day_orders)
                                  and i.unit_cost is null)
                        """)
                .param("business", business).param("from", from).param("to", to)
                .query((rs, n) -> new Totals(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5)))
                .single();

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (OrderStatus status : OrderStatus.values()) {
            byStatus.put(status.name(), 0);
        }
        jdbc.sql("""
                        select status, count(*) from orders
                        where business_id = :business
                          and (status in ('DRAFT', 'CONFIRMED', 'PACKED', 'SHIPPED')
                               or (status = 'DELIVERED' and delivered_at >= :from and delivered_at < :to)
                               or (status = 'RETURNED' and returned_at >= :from and returned_at < :to)
                               or (status = 'CANCELLED' and cancelled_at >= :from and cancelled_at < :to))
                        group by status
                        """)
                .param("business", business).param("from", from).param("to", to)
                .query((rs, n) -> byStatus.put(rs.getString(1), rs.getInt(2)))
                .list();

        record Activity(int cancelled, int shipped, int delivered, long inTransit, long withCourier) {
        }
        Activity activity = jdbc.sql("""
                        select (select count(*) from orders where business_id = :business
                                and cancelled_at >= :from and cancelled_at < :to),
                               (select count(*) from shipments where business_id = :business
                                and shipped_at >= :from and shipped_at < :to),
                               (select count(*) from orders where business_id = :business
                                and delivered_at >= :from and delivered_at < :to),
                               (select coalesce(sum(cod_expected), 0) from shipments where business_id = :business
                                and status = 'IN_TRANSIT' and cod_status = 'WITH_COURIER'),
                               (select coalesce(sum(cod_collected), 0) from shipments where business_id = :business
                                and status = 'DELIVERED' and cod_status = 'WITH_COURIER')
                        """)
                .param("business", business).param("from", from).param("to", to)
                .query((rs, n) -> new Activity(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getLong(5)))
                .single();

        // What customers kept: ordered less returned.
        List<TopProduct> top = jdbc.sql("with " + DAY_ORDERS + """
                        select i.product_name, sum(i.quantity - coalesce(r.quantity, 0)) as kept,
                               sum((i.quantity - coalesce(r.quantity, 0)) * i.unit_price)
                        from order_items i left join returned r on r.order_item_id = i.id
                        where i.business_id = :business and i.order_id in (select id from day_orders)
                        group by i.product_name
                        having sum(i.quantity - coalesce(r.quantity, 0)) > 0
                        order by kept desc, 3 desc, i.product_name
                        limit :limit
                        """)
                .param("business", business).param("from", from).param("to", to).param("limit", TOP_PRODUCTS)
                .query((rs, n) -> new TopProduct(rs.getString(1), rs.getInt(2), rs.getLong(3)))
                .list();

        return new Day(date, totals.orders(), totals.value(), activity.cancelled(), byStatus, activity.shipped(),
                activity.delivered(), activity.inTransit(), activity.withCourier(), top,
                new Profit(totals.revenue(), totals.cost(), totals.revenue() - totals.cost(), totals.costMissing()));
    }

    @Override
    @Transactional(readOnly = true)
    public Deliveries deliveries(Instant lateBefore, Instant stuckBefore, Instant shippedSince) {
        UUID business = TenantScope.current().orElseThrow();
        Timestamp since = Timestamp.from(shippedSince);

        int late = jdbc.sql("""
                        select count(*) from shipments
                        where business_id = :business and status = 'IN_TRANSIT' and shipped_at < :before
                        """)
                .param("business", business).param("before", Timestamp.from(lateBefore))
                .query(Integer.class).single();

        List<CourierCash> stuck = jdbc.sql("""
                        select courier, sum(cod_collected) from shipments
                        where business_id = :business and status = 'DELIVERED' and cod_status = 'WITH_COURIER'
                          and delivered_at < :before and cod_collected > 0
                        group by courier
                        order by 2 desc, courier
                        """)
                .param("business", business).param("before", Timestamp.from(stuckBefore))
                .query((rs, n) -> new CourierCash(rs.getString(1), rs.getLong(2)))
                .list();

        List<CourierParcels> couriers = jdbc.sql("""
                        select courier,
                               count(*) filter (where status = 'IN_TRANSIT'),
                               count(*) filter (where status = 'DELIVERED' and shipped_at >= :since),
                               count(*) filter (where status = 'RETURNED' and shipped_at >= :since)
                        from shipments
                        where business_id = :business and (status = 'IN_TRANSIT' or shipped_at >= :since)
                        group by courier
                        order by count(*) filter (where status <> 'IN_TRANSIT' and shipped_at >= :since) desc, 2 desc, courier
                        """)
                .param("business", business).param("since", since)
                .query((rs, n) -> new CourierParcels(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
                .list();

        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (ReturnKind kind : ReturnKind.values()) {
            reasons.put(kind.name(), 0);
        }
        jdbc.sql("""
                        select r.kind, count(*) from returns r
                        join shipments s on s.business_id = r.business_id and s.id = r.shipment_id
                        where r.business_id = :business and s.shipped_at >= :since
                        group by r.kind
                        """)
                .param("business", business).param("since", since)
                .query((rs, n) -> reasons.put(rs.getString(1), rs.getInt(2)))
                .list();

        boolean hasOrders = jdbc.sql("select exists (select 1 from orders where business_id = :business)")
                .param("business", business).query(Boolean.class).single();
        return new Deliveries(late, stuck, couriers, reasons, hasOrders);
    }

    /** What the customer still owes for goods (after discount and returns), as SQL on day_orders. */
    private static final String GOODS_KEPT = "(subtotal - discount) - least(return_credit, subtotal - discount)";
    /** The delivery charge the customer still owes: what they owe less the goods (none on a failed delivery). */
    private static final String DELIVERY_KEPT = "greatest(0, (total - return_credit) - (" + GOODS_KEPT + "))";

    @Override
    @Transactional(readOnly = true)
    public List<DaySales> salesByDay(LocalDate from, LocalDate to) {
        UUID business = TenantScope.current().orElseThrow();
        Map<LocalDate, DaySales> byDay = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byDay.put(day, new DaySales(day, 0, 0, 0, 0));
        }
        jdbc.sql(("with " + DAY_ORDERS + """
                        , kept as (
                            select i.order_id, sum(i.quantity - coalesce(r.quantity, 0)) as quantity
                            from order_items i left join returned r on r.order_item_id = i.id
                            where i.business_id = :business and i.order_id in (select id from day_orders)
                            group by i.order_id
                        )
                        select (o.confirmed_at at time zone 'Asia/Dhaka')::date, count(*), coalesce(sum(k.quantity), 0),
                               sum(%s), sum(%s)
                        from day_orders o left join kept k on k.order_id = o.id
                        group by 1
                        """).formatted(GOODS_KEPT, DELIVERY_KEPT))
                .param("business", business).param("from", start(from)).param("to", start(to.plusDays(1)))
                .query((rs, n) -> {
                    LocalDate day = rs.getObject(1, LocalDate.class);
                    return byDay.put(day, new DaySales(day, rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getLong(5)));
                })
                .list();
        return List.copyOf(byDay.values());
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantSales> salesByVariant(LocalDate from, LocalDate to) {
        return jdbc.sql("with " + DAY_ORDERS + """
                        select i.variant_id, min(i.product_name), min(i.variant_label),
                               sum(i.quantity - coalesce(r.quantity, 0)),
                               sum((i.quantity - coalesce(r.quantity, 0)) * i.unit_price)
                        from order_items i left join returned r on r.order_item_id = i.id
                        where i.business_id = :business and i.order_id in (select id from day_orders)
                        group by i.variant_id
                        having sum(i.quantity - coalesce(r.quantity, 0)) > 0
                        order by 5 desc, 2, 3
                        """)
                .param("business", TenantScope.current().orElseThrow())
                .param("from", start(from)).param("to", start(to.plusDays(1)))
                .query((rs, n) -> new VariantSales(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getLong(5)))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public ProfitReport profit(LocalDate from, LocalDate to) {
        return jdbc.sql(("with " + DAY_ORDERS + """
                        , written_off as (
                            select ri.order_item_id, sum(ri.quantity) as quantity
                            from return_items ri join returns r on r.id = ri.return_id
                            where r.business_id = :business and r.order_id in (select id from day_orders)
                              and ri.disposition = 'WRITE_OFF'
                            group by ri.order_item_id
                        )
                        select (select count(*) from day_orders),
                               (select coalesce(sum(%s), 0) from day_orders),
                               (select coalesce(sum(%s), 0) from day_orders),
                               -- Goods kept: ordered less everything that came back.
                               (select coalesce(sum((i.quantity - coalesce(r.quantity, 0)) * i.unit_cost), 0)
                                from order_items i left join returned r on r.order_item_id = i.id
                                where i.business_id = :business and i.order_id in (select id from day_orders)
                                  and i.unit_cost is not null),
                               (select coalesce(sum(w.quantity * i.unit_cost), 0)
                                from order_items i join written_off w on w.order_item_id = i.id
                                where i.business_id = :business and i.unit_cost is not null),
                               (select coalesce(sum(s.courier_charge), 0) from shipments s
                                where s.business_id = :business and s.order_id in (select id from day_orders)),
                               (select count(*) from order_items i
                                where i.business_id = :business and i.order_id in (select id from day_orders)
                                  and i.unit_cost is null)
                        """).formatted(GOODS_KEPT, DELIVERY_KEPT))
                .param("business", TenantScope.current().orElseThrow())
                .param("from", start(from)).param("to", start(to.plusDays(1)))
                .query((rs, n) -> {
                    long revenue = rs.getLong(2);
                    long delivery = rs.getLong(3);
                    long cogs = rs.getLong(4);
                    long writeOffs = rs.getLong(5);
                    long courier = rs.getLong(6);
                    long gross = revenue - cogs - writeOffs;
                    return new ProfitReport(rs.getInt(1), revenue, cogs, writeOffs, gross, delivery, courier,
                            delivery - courier, gross + delivery - courier, rs.getInt(7));
                })
                .single();
    }

    private static Timestamp start(LocalDate day) {
        return Timestamp.from(DhakaTime.startOf(day));
    }
}
