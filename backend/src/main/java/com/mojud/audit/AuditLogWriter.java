package com.mojud.audit;

import com.mojud.shared.audit.AuditEvent;
import com.mojud.shared.id.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes audit entries synchronously, on the publisher's connection and in its transaction. A plain
 * {@code @EventListener} on purpose: an entry that failed to write takes the change down with it.
 */
@Component
class AuditLogWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    AuditLogWriter(DataSource dataSource, JsonMapper json, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.clock = clock;
    }

    @EventListener
    void write(AuditEvent event) {
        jdbc.sql("""
                        insert into audit_logs (id, business_id, actor_id, action, entity_type, entity_id, details, client_ip, created_at)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """)
                .params(Ids.newId(), event.businessId(), event.actorId(), event.action(), event.entityType(),
                        event.entityId(), json.writeValueAsString(event.details()), clientIp(),
                        Timestamp.from(clock.instant()))
                .update();
    }

    private static String clientIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? request.getRequest().getRemoteAddr()
                : null;
    }
}
