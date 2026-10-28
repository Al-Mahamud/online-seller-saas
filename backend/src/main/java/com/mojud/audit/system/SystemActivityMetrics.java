package com.mojud.audit.system;

import com.mojud.audit.ActivityMetrics;
import com.mojud.shared.db.system.SystemDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Active: a member did something recorded in the business's audit log (jobs and the system do not count). */
@Component
class SystemActivityMetrics implements ActivityMetrics {

    private final SystemDatabase database;

    SystemActivityMetrics(SystemDatabase database) {
        this.database = database;
    }

    @Override
    public long activeBusinessesSince(Instant since) {
        return database.jdbc().sql("select count(distinct business_id) from audit_logs where actor_id is not null and created_at >= ?")
                .param(Timestamp.from(since)).query(Long.class).single();
    }
}
