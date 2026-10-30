package com.mojud.orders;

import com.mojud.identity.UserDirectory;
import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The order's status history (order_events, append-only), written in the transaction of the change. */
@Component
class OrderEvents {

    /** One step of the order's timeline: {@code from} null for a new order, {@code by} the person's name. */
    record View(@Nullable OrderStatus from, OrderStatus to, String note, @Nullable String by, Instant at) {
    }

    private final JdbcClient jdbc;
    private final UserDirectory users;

    OrderEvents(DataSource dataSource, UserDirectory users) {
        this.jdbc = JdbcClient.create(dataSource);
        this.users = users;
    }

    /** Oldest first. */
    List<View> forOrder(UUID orderId) {
        record Row(String from, String to, String note, UUID userId, Instant at) {
        }
        List<Row> rows = jdbc.sql("""
                        select from_status, to_status, note, user_id, created_at from order_events
                        where order_id = ? order by created_at, id
                        """)
                .param(orderId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getTimestamp(5).toInstant()))
                .list();
        Map<UUID, UserDirectory.UserSummary> people = users.summaries(
                rows.stream().map(Row::userId).filter(Objects::nonNull).distinct().toList());
        return rows.stream().map(row -> new View(row.from() == null ? null : OrderStatus.valueOf(row.from()),
                OrderStatus.valueOf(row.to()), row.note(),
                people.containsKey(row.userId()) ? people.get(row.userId()).name() : null, row.at())).toList();
    }

    /** The order must already be written (flushed): this is plain SQL. {@code from} null for a new order. */
    @Transactional(propagation = Propagation.MANDATORY)
    void record(UUID orderId, OrderStatus from, OrderStatus to, UUID userId, String note, Instant at) {
        jdbc.sql("""
                        insert into order_events (id, business_id, order_id, from_status, to_status, user_id, note, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(Ids.newId(), TenantScope.current().orElseThrow(), orderId, from == null ? null : from.name(),
                        to.name(), userId, note, Timestamp.from(at))
                .update();
    }
}
