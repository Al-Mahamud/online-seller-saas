package com.mojud.orders;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Returns and their items (implementation-plan.md 8c), and the checks on what may come back: never
 * more of an item than the shipment carried, counting earlier returns from it.
 */
@Component
class OrderReturns {

    enum Disposition {
        /** Back on the shelf (a RETURN movement). */
        RESTOCK,
        /** Damaged or lost: recorded (RETURN_DAMAGED), not back in stock. */
        WRITE_OFF
    }

    /** One item coming back, and what becomes of it. */
    record Line(UUID orderItemId, int quantity, Disposition disposition) {
    }

    /** A refund given with a return, in poisha. */
    record Refund(long amount, PaymentMethod method, String reference) {
    }

    record ItemView(UUID orderItemId, String productName, String variantLabel, String sku, int quantity,
            Disposition disposition) {
    }

    record View(UUID id, ReturnKind kind, String reason, long credit, long refundAmount, PaymentMethod refundMethod,
            String refundReference, List<ItemView> items, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    OrderReturns(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Checks the lines against the order's items and what already came back from this shipment.
     * {@code whole}: every item must come back in full (a failed delivery).
     *
     * @return the lines' value at their unit prices, in poisha
     */
    long check(List<Line> lines, List<OrderItem> items, UUID shipmentId, boolean whole) {
        if (lines.isEmpty()) {
            throw ApiException.invalidField("items", "field.return_items");
        }
        Map<UUID, OrderItem> byId = new HashMap<>();
        items.forEach(item -> byId.put(item.getId(), item));
        Map<UUID, Integer> earlier = returnedFrom(shipmentId);
        Map<UUID, Integer> now = new HashMap<>();
        Set<String> seen = new HashSet<>();
        long value = 0;
        for (Line line : lines) {
            OrderItem item = byId.get(line.orderItemId());
            if (item == null || line.quantity() < 1 || line.disposition() == null) {
                throw ApiException.invalidField("items", "field.invalid_value");
            }
            if (!seen.add(line.orderItemId() + ":" + line.disposition())) {
                throw ApiException.invalidField("items", "field.variant_repeated");
            }
            now.merge(item.getId(), line.quantity(), Integer::sum);
            value = Math.addExact(value, Math.multiplyExact((long) line.quantity(), item.getUnitPrice()));
        }
        for (OrderItem item : items) {
            int back = earlier.getOrDefault(item.getId(), 0) + now.getOrDefault(item.getId(), 0);
            if (back > item.getQuantity()) {
                throw ApiException.invalidField("items", "field.return_more_than_shipped");
            }
            if (whole && back != item.getQuantity()) {
                throw ApiException.invalidField("items", "field.return_whole_parcel");
            }
        }
        return value;
    }

    /** True when these lines, with earlier returns from this shipment, bring every item back in full. */
    boolean allBackWith(List<Line> lines, List<OrderItem> items, UUID shipmentId) {
        Map<UUID, Integer> back = returnedFrom(shipmentId);
        lines.forEach(line -> back.merge(line.orderItemId(), line.quantity(), Integer::sum));
        return items.stream().allMatch(item -> back.getOrDefault(item.getId(), 0) == item.getQuantity());
    }

    /** True when, with earlier returns from this shipment, every item has come back in full. */
    boolean allBack(List<OrderItem> items, UUID shipmentId) {
        Map<UUID, Integer> back = returnedFrom(shipmentId);
        return items.stream().allMatch(item -> back.getOrDefault(item.getId(), 0) == item.getQuantity());
    }

    UUID save(UUID orderId, UUID shipmentId, ReturnKind kind, String reason, long credit, Refund refund, List<Line> lines,
            UUID userId, Instant at) {
        UUID id = Ids.newId();
        UUID business = TenantScope.current().orElseThrow();
        jdbc.sql("""
                        insert into returns (id, business_id, order_id, shipment_id, kind, reason, credit, refund_amount,
                                             refund_method, refund_reference, created_by, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(id, business, orderId, shipmentId, kind.name(), reason, credit,
                        refund == null ? 0 : refund.amount(), refund == null ? null : refund.method().name(),
                        refund == null ? "" : refund.reference(), userId, Timestamp.from(at))
                .update();
        for (Line line : lines) {
            jdbc.sql("insert into return_items (business_id, return_id, order_item_id, quantity, disposition) values (?, ?, ?, ?, ?)")
                    .params(business, id, line.orderItemId(), line.quantity(), line.disposition().name())
                    .update();
        }
        return id;
    }

    List<View> forOrder(UUID orderId, List<OrderItem> items) {
        Map<UUID, OrderItem> byId = new HashMap<>();
        items.forEach(item -> byId.put(item.getId(), item));
        Map<UUID, List<ItemView>> lines = new HashMap<>();
        jdbc.sql("""
                        select ri.return_id, ri.order_item_id, ri.quantity, ri.disposition from return_items ri
                        join returns r on r.id = ri.return_id where r.order_id = ?
                        order by ri.order_item_id, ri.disposition
                        """)
                .param(orderId)
                .query((rs, n) -> {
                    OrderItem item = byId.get(rs.getObject(2, UUID.class));
                    lines.computeIfAbsent(rs.getObject(1, UUID.class), key -> new ArrayList<>()).add(new ItemView(
                            item.getId(), item.getProductName(), item.getVariantLabel(), item.getSku(), rs.getInt(3),
                            Disposition.valueOf(rs.getString(4))));
                    return null;
                })
                .list();
        Map<UUID, View> views = new LinkedHashMap<>();
        jdbc.sql("""
                        select id, kind, reason, credit, refund_amount, refund_method, refund_reference, created_at
                        from returns where order_id = ? order by created_at, id
                        """)
                .param(orderId)
                .query((rs, n) -> new View(rs.getObject(1, UUID.class), ReturnKind.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getLong(4), rs.getLong(5),
                        rs.getString(6) == null ? null : PaymentMethod.valueOf(rs.getString(6)), rs.getString(7),
                        List.of(), rs.getTimestamp(8).toInstant()))
                .list()
                .forEach(view -> views.put(view.id(), view));
        return views.values().stream().map(view -> new View(view.id(), view.kind(), view.reason(), view.credit(),
                view.refundAmount(), view.refundMethod(), view.refundReference(),
                List.copyOf(lines.getOrDefault(view.id(), List.of())), view.createdAt())).toList();
    }

    private Map<UUID, Integer> returnedFrom(UUID shipmentId) {
        Map<UUID, Integer> back = new HashMap<>();
        jdbc.sql("""
                        select ri.order_item_id, sum(ri.quantity) from return_items ri
                        join returns r on r.id = ri.return_id where r.shipment_id = ?
                        group by ri.order_item_id
                        """)
                .param(shipmentId)
                .query((rs, n) -> back.put(rs.getObject(1, UUID.class), rs.getInt(2)))
                .list();
        return back;
    }
}
