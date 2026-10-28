package com.mojud.tenancy.system;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessDeletion;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Deleting businesses (implementation-plan.md 12b) through mojud_delete_business (V31), which
 * empties every table with a business_id, children first, as the schema owner. Daily at 03:30
 * Dhaka, the businesses whose undo window has passed.
 */
@Component
public class BusinessDeletions implements BusinessDeletion {

    private static final Logger log = LoggerFactory.getLogger(BusinessDeletions.class);

    private final SystemDatabase database;
    private final Clock clock;

    BusinessDeletions(SystemDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    @Override
    public int deleteNow(UUID businessId) {
        int rows = database.jdbc().sql("select mojud_delete_business(?)").param(businessId).query(Integer.class).single();
        log.info("Business {} deleted ({} rows)", businessId, rows);
        return rows;
    }

    /** @return the businesses deleted */
    public List<UUID> run() {
        List<UUID> due = database.jdbc().sql("select id from businesses where deletion_due_at <= ? order by deletion_due_at")
                .param(Timestamp.from(clock.instant())).query(UUID.class).list();
        for (UUID business : due) {
            try {
                deleteNow(business);
            } catch (RuntimeException e) {
                log.error("Deleting business {} failed", business, e);
            }
        }
        return due;
    }

    @Bean
    RecurringTask<Void> businessDeletionTask() {
        return Tasks.recurring("business-deletion", Schedules.cron("0 30 3 * * *", DhakaTime.ZONE))
                .execute((instance, context) -> run());
    }
}
