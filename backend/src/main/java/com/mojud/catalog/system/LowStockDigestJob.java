package com.mojud.catalog.system;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.time.DhakaTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * The low-stock digest email (implementation-plan.md 13a), daily at 08:00 Dhaka: every active
 * business (system role to find them), each then read as itself. In-app alerts stay immediate.
 */
@Component
public class LowStockDigestJob {

    private static final Logger log = LoggerFactory.getLogger(LowStockDigestJob.class);

    private final SystemDatabase database;
    private final LowStockDigests digests;

    LowStockDigestJob(SystemDatabase database, LowStockDigests digests) {
        this.database = database;
        this.digests = digests;
    }

    /** @return how many emails were sent. One business failing does not stop the rest. */
    public int run() {
        List<UUID> businesses = database.jdbc().sql("select id from businesses where status = 'ACTIVE' and deletion_due_at is null")
                .query(UUID.class).list();
        int sent = 0;
        for (UUID business : businesses) {
            try {
                sent += digests.send(business);
            } catch (RuntimeException e) {
                log.error("Low-stock digest failed for business {}", business, e);
            }
        }
        log.info("Low-stock digest: {} businesses, {} emails", businesses.size(), sent);
        return sent;
    }

    @Bean
    RecurringTask<Void> lowStockDigestTask() {
        return Tasks.recurring("low-stock-digest", Schedules.cron("0 0 8 * * *", DhakaTime.ZONE))
                .execute((instance, context) -> run());
    }
}
