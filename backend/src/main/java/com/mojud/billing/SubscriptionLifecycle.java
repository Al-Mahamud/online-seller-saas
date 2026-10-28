package com.mojud.billing;

import com.mojud.billing.system.SubscriptionAdvancer;
import com.mojud.notifications.Notifier;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The subscription lifecycle (implementation-plan.md 10b, backend-plan.md §8), one business at a
 * time, as that business:
 * <ul>
 * <li>TRIALING: at the trial's end, READ_ONLY (no grace: nothing was paid);
 * <li>ACTIVE: at the period's end unpaid, GRACE for 7 days, with a reminder each day;
 * <li>GRACE: at its end, READ_ONLY;
 * <li>reminders 7, 3 and 1 day before a trial or a period ends; warnings 60 and 85 days into
 * READ_ONLY, before data deletion at 90.
 * </ul>
 * Notifications go to the owner, at most one of each kind a day (dedupe keys), so a second run on
 * the same day changes nothing.
 */
@Component
class SubscriptionLifecycle implements SubscriptionAdvancer {

    static final Duration GRACE = Duration.ofDays(7);
    static final Set<Long> REMINDER_DAYS = Set.of(7L, 3L, 1L);
    static final Set<Long> WARNING_DAYS = Set.of(60L, 85L);
    static final long DELETION_DAYS = 90;

    private record Row(String status, Instant trialEndsAt, Instant periodEnd, Instant graceEndsAt, Instant readOnlySince) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Notifier notifier;
    private final AuditTrail audit;
    private final Clock clock;

    SubscriptionLifecycle(DataSource dataSource, PlatformTransactionManager transactionManager, Notifier notifier,
            AuditTrail audit, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.notifier = notifier;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public void advance(UUID businessId) {
        TenantScope.run(businessId, () -> transactions.executeWithoutResult(status -> step(businessId)));
    }

    private void step(UUID business) {
        Row row = jdbc.sql("""
                        select status, trial_ends_at, current_period_end, grace_ends_at, read_only_since
                        from subscriptions where business_id = ? for update
                        """)
                .param(business)
                .query((rs, n) -> new Row(rs.getString(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3)),
                        instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5))))
                .optional().orElse(null);
        if (row == null) {
            return;
        }
        Instant now = clock.instant();
        LocalDate today = DhakaTime.today(clock);
        switch (row.status()) {
            case "TRIALING" -> {
                if (row.trialEndsAt() != null && !now.isBefore(row.trialEndsAt())) {
                    readOnly(business, "TRIALING", now);
                    notify("billing.trial_ended", List.of(), "billing.trial_ended:" + today);
                } else if (row.trialEndsAt() != null) {
                    remind("billing.trial_ending", row.trialEndsAt(), today);
                }
            }
            case "ACTIVE" -> {
                if (row.periodEnd() != null && !now.isBefore(row.periodEnd())) {
                    Instant graceEnds = row.periodEnd().plus(GRACE);
                    jdbc.sql("update subscriptions set status = 'GRACE', grace_ends_at = ?, updated_at = ? where business_id = ?")
                            .params(Timestamp.from(graceEnds), Timestamp.from(now), business).update();
                    changed(business, "ACTIVE", "GRACE");
                    grace(graceEnds, today);
                } else if (row.periodEnd() != null) {
                    remind("billing.period_ending", row.periodEnd(), today);
                }
            }
            case "GRACE" -> {
                if (row.graceEndsAt() != null && !now.isBefore(row.graceEndsAt())) {
                    readOnly(business, "GRACE", now);
                    notify("billing.read_only", List.of(), "billing.read_only:" + today);
                } else if (row.graceEndsAt() != null) {
                    grace(row.graceEndsAt(), today);
                }
            }
            case "READ_ONLY" -> {
                if (row.readOnlySince() != null) {
                    long days = ChronoUnit.DAYS.between(DhakaTime.today(Clock.fixed(row.readOnlySince(), DhakaTime.ZONE)), today);
                    if (WARNING_DAYS.contains(days)) {
                        notify("billing.deletion_warning", List.of(DELETION_DAYS - days), "billing.deletion_warning:" + days);
                    }
                }
            }
            default -> {
                // SUSPENDED: an admin decides.
            }
        }
    }

    private void readOnly(UUID business, String from, Instant now) {
        jdbc.sql("update subscriptions set status = 'READ_ONLY', read_only_since = ?, updated_at = ? where business_id = ?")
                .params(Timestamp.from(now), Timestamp.from(now), business).update();
        changed(business, from, "READ_ONLY");
    }

    private void changed(UUID business, String from, String to) {
        audit.record(business, null, "subscription.changed", "business", business, "from", from, "to", to);
    }

    /** 7, 3 and 1 day before {@code end} (in Dhaka days). */
    private void remind(String type, Instant end, LocalDate today) {
        LocalDate endDay = DhakaTime.today(Clock.fixed(end, DhakaTime.ZONE));
        long days = ChronoUnit.DAYS.between(today, endDay);
        if (REMINDER_DAYS.contains(days)) {
            notify(type, List.of(days), type + ":" + endDay + ":" + days);
        }
    }

    private void grace(Instant graceEnds, LocalDate today) {
        long days = Math.max(1, ChronoUnit.DAYS.between(today, DhakaTime.today(Clock.fixed(graceEnds, DhakaTime.ZONE))));
        notify("billing.grace", List.of(days), "billing.grace:" + today);
    }

    private void notify(String type, List<Object> args, String dedupeKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("args", args);
        notifier.notify(type, BusinessRole.OWNER, payload, dedupeKey);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
