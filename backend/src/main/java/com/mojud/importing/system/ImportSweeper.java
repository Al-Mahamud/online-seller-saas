package com.mojud.importing.system;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.mojud.shared.db.system.SystemDatabase;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Every minute, runs imports that were queued but never started (the app stopped before it got to
 * them) and resumes those whose heartbeat stopped (the app stopped while running them). Finds them
 * across businesses, so it uses the system role; the import itself runs as its business.
 */
@Component
public class ImportSweeper {

    private static final Logger log = LoggerFactory.getLogger(ImportSweeper.class);
    /** A queued import normally starts at once; this long means its start was lost. */
    static final Duration QUEUED_TOO_LONG = Duration.ofMinutes(1);
    /** A running import beats after every chunk, which takes seconds. */
    public static final Duration STALLED = Duration.ofMinutes(5);

    private record Waiting(UUID businessId, UUID jobId) {
    }

    private final SystemDatabase database;
    private final ImportResumer resumer;
    private final Clock clock;

    ImportSweeper(SystemDatabase database, ImportResumer resumer, Clock clock) {
        this.database = database;
        this.resumer = resumer;
        this.clock = clock;
    }

    /** @return how many imports it started or resumed */
    public int run() {
        List<Waiting> waiting = database.jdbc().sql("""
                        select business_id, id from import_jobs
                        where (status = 'QUEUED' and queued_at < ?) or (status = 'RUNNING' and heartbeat_at < ?)
                        order by queued_at
                        """)
                .params(Timestamp.from(clock.instant().minus(QUEUED_TOO_LONG)), Timestamp.from(clock.instant().minus(STALLED)))
                .query((row, n) -> new Waiting(row.getObject(1, UUID.class), row.getObject(2, UUID.class)))
                .list();
        for (Waiting job : waiting) {
            log.warn("[IMPORT-RESUME] import {} of business {}", job.jobId(), job.businessId());
            resumer.run(job.businessId(), job.jobId());
        }
        return waiting.size();
    }

    @Bean
    RecurringTask<Void> importSweeperTask() {
        return Tasks.recurring("import-sweeper", Schedules.fixedDelay(Duration.ofMinutes(1)))
                .execute((instance, context) -> run());
    }
}
