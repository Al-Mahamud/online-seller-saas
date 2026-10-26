package com.mojud.notifications;

import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * How modules notify a business's members in the app. Runs in the caller's transaction, so a
 * notification exists exactly when its cause does. The title is written at read time, in the
 * reader's language, from {@code type} and {@code payload} (NotificationController).
 */
@Component
public class Notifier {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    Notifier(DataSource dataSource, JsonMapper json, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param type a dotted name with a message {@code notification.<type>}, e.g. {@code stock.low}
     * @param audience the lowest role that sees it
     * @param dedupeKey with an existing notification of the same key, nothing happens; null: always notify
     * @return true when a notification was created
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean notify(String type, BusinessRole audience, Map<String, Object> payload, String dedupeKey) {
        return jdbc.sql("""
                        insert into notifications (id, business_id, type, audience, payload, dedupe_key, created_at)
                        values (?, ?, ?, ?, ?::jsonb, ?, ?)
                        on conflict (business_id, dedupe_key) where dedupe_key is not null do nothing
                        """)
                .params(Ids.newId(), TenantScope.current().orElseThrow(), type, audience.name(),
                        json.writeValueAsString(payload), dedupeKey, Timestamp.from(clock.instant()))
                .update() == 1;
    }
}
