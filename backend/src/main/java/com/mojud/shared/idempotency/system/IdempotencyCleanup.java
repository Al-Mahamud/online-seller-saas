package com.mojud.shared.idempotency.system;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.time.DhakaTime;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Nightly removal of idempotency keys older than a day: a resend comes within minutes, so a day
 * is plenty, and the table stays small. Runs over every business, so it uses the system role.
 */
@Component
public class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);
    static final Duration KEEP = Duration.ofDays(1);

    private final SystemDatabase database;
    private final Clock clock;

    IdempotencyCleanup(SystemDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public int run() {
        int removed = database.jdbc().sql("delete from idempotency_keys where created_at < ?")
                .param(Timestamp.from(clock.instant().minus(KEEP)))
                .update();
        log.info("Idempotency cleanup: {} key(s) removed", removed);
        return removed;
    }

    @Bean
    RecurringTask<Void> idempotencyCleanupTask() {
        return Tasks.recurring("idempotency-cleanup", Schedules.cron("0 0 3 * * *", DhakaTime.ZONE))
                .execute((instance, context) -> run());
    }
}
