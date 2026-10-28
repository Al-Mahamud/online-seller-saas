package com.mojud.billing.system;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessDeletion;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Daily billing jobs across all businesses (system role to find them; each one is then changed as
 * its own business).
 */
@Component
public class SubscriptionJobs {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionJobs.class);
    static final Duration KEEP_READ_ONLY = Duration.ofDays(90);

    private final SystemDatabase database;
    private final SubscriptionAdvancer advancer;
    private final BusinessDeletion deletion;
    private final Clock clock;
    private final boolean deletionEnabled;

    SubscriptionJobs(SystemDatabase database, SubscriptionAdvancer advancer, BusinessDeletion deletion, Clock clock,
            @Value("${mojud.billing.data-deletion.enabled:false}") boolean deletionEnabled) {
        this.database = database;
        this.advancer = advancer;
        this.deletion = deletion;
        this.clock = clock;
        this.deletionEnabled = deletionEnabled;
    }

    /** 09:00 Dhaka: every subscription moved along and reminded. One business failing does not stop the rest. */
    public int lifecycle() {
        List<UUID> businesses = database.jdbc().sql("select business_id from subscriptions where status <> 'SUSPENDED'")
                .query(UUID.class).list();
        int failed = 0;
        for (UUID business : businesses) {
            try {
                advancer.advance(business);
            } catch (RuntimeException e) {
                failed++;
                log.error("Subscription lifecycle failed for business {}", business, e);
            }
        }
        log.info("Subscription lifecycle: {} businesses, {} failed", businesses.size(), failed);
        return businesses.size();
    }

    /**
     * Businesses READ_ONLY for over 90 days, whose data is deleted. Shipped disabled
     * ({@code mojud.billing.data-deletion.enabled}) until the warnings' wording is approved: then it
     * only lists them.
     */
    public List<UUID> dataDeletion() {
        List<UUID> due = database.jdbc().sql("select business_id from subscriptions where status = 'READ_ONLY' and read_only_since < ?")
                .param(Timestamp.from(clock.instant().minus(KEEP_READ_ONLY)))
                .query(UUID.class).list();
        if (!deletionEnabled) {
            log.info("Data deletion is disabled; {} business(es) are past 90 days read-only", due.size());
            return due;
        }
        for (UUID business : due) {
            try {
                deletion.deleteNow(business);
            } catch (RuntimeException e) {
                log.error("Deleting read-only business {} failed", business, e);
            }
        }
        return due;
    }

    @Bean
    RecurringTask<Void> subscriptionLifecycleTask() {
        return Tasks.recurring("subscription-lifecycle", Schedules.cron("0 0 9 * * *", DhakaTime.ZONE))
                .execute((instance, context) -> lifecycle());
    }

    @Bean
    RecurringTask<Void> dataDeletionTask() {
        return Tasks.recurring("data-deletion", Schedules.cron("0 30 9 * * *", DhakaTime.ZONE))
                .execute((instance, context) -> dataDeletion());
    }
}
