package com.mojud.orders;

import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The order's status history (order_events, append-only), written in the transaction of the change. */
@Component
class OrderEvents {

    private final JdbcClient jdbc;

    OrderEvents(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
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
