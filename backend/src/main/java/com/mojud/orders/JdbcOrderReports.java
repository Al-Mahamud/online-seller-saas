package com.mojud.orders;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.time.DhakaTime;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                select id, total, subtotal, discount, return_credit from orders
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
}
