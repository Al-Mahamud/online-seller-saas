package com.mojud.identity.system;

import com.mojud.identity.UserMetrics;
import com.mojud.shared.db.system.SystemDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
class SystemUserMetrics implements UserMetrics {

    private final SystemDatabase database;

    SystemUserMetrics(SystemDatabase database) {
        this.database = database;
    }

    @Override
    public long users() {
        return database.jdbc().sql("select count(*) from users").query(Long.class).single();
    }

    @Override
    public long signUpsSince(Instant since) {
        return database.jdbc().sql("select count(*) from users where created_at >= ?").param(Timestamp.from(since))
                .query(Long.class).single();
    }
}
