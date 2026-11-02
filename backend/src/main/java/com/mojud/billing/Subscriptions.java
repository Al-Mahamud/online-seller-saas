package com.mojud.billing;

import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessCreated;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each business's subscription (implementation-plan.md 10a). A new business starts on Free, ACTIVE
 * with no period: it never ends. Starter and Pro are paid for some months at a time. (The trial of
 * old, plan TRIAL, is no longer given; V36 moved every business on it to Free.)
 */
@Component
class Subscriptions {

    /** The plan every business starts on, and comes back to: no price, no period. */
    static final String FREE = "FREE";

    record Subscription(String planCode, String status, Instant trialEndsAt, Instant currentPeriodStart,
            Instant currentPeriodEnd, String pendingPlanCode, Instant graceEndsAt) {
    }

    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Clock clock;

    Subscriptions(DataSource dataSource, AuditTrail audit, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.audit = audit;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(BusinessCreated event) {
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into subscriptions (business_id, plan_code, status, created_at, updated_at, version)
                        values (?, ?, 'ACTIVE', ?, ?, 0)
                        """)
                .params(event.businessId(), FREE, Timestamp.from(now), Timestamp.from(now))
                .update();
    }

    /**
     * A plan change asked for by the owner. A cheaper plan waits for the renewal (pending); the
     * current plan cancels a waiting change; Free, with no paid period running, starts at once;
     * anything that needs paying is a checkout or an upgrade.
     */
    @Transactional
    void choose(Plans.Plan target, Plans.Plan current, UUID ownerId) {
        UUID business = TenantScope.current().orElseThrow();
        String status = jdbc.sql("select status from subscriptions where business_id = ? for update")
                .param(business).query(String.class).single();
        // Free needs no paying: without a paid period running, it starts now.
        if (target.code().equals(FREE) && !status.equals("ACTIVE") && !status.equals("SUSPENDED")) {
            toFree(business, status, ownerId);
            return;
        }
        boolean paid = status.equals("ACTIVE") || status.equals("GRACE");
        if (!paid || target.priceMonthly() > current.priceMonthly()) {
            throw new ApiException(ErrorCode.BILLING_PAYMENT_NEEDED);
        }
        String pending = target.code().equals(current.code()) ? null : target.code();
        jdbc.sql("update subscriptions set pending_plan_code = ?, updated_at = ? where business_id = ?")
                .params(pending, Timestamp.from(clock.instant()), business).update();
        audit.record(business, ownerId, "subscription.plan_scheduled", "business", business, "plan", pending);
    }

    record Period(Instant start, Instant end) {
    }

    /**
     * A paid invoice for {@code months} of {@code planCode}: the subscription becomes ACTIVE on that
     * plan. The new period follows a period still running, or a trial still running (its days are
     * not lost); otherwise it starts now. A waiting downgrade is settled by what was paid for. A
     * payment that arrives while the business is suspended counts, and the suspension stands: only
     * an administrator lifts it, and it then comes back ACTIVE.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Period extend(String planCode, int months) {
        UUID business = TenantScope.current().orElseThrow();
        Instant now = clock.instant();
        record Row(String status, Instant trialEndsAt, Instant periodEnd) {
        }
        Row row = jdbc.sql("select status, trial_ends_at, current_period_end from subscriptions where business_id = ? for update")
                .param(business)
                .query((rs, n) -> new Row(rs.getString(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .single();
        Instant start = now;
        boolean extending = row.status().equals("ACTIVE") && row.periodEnd() != null && row.periodEnd().isAfter(now);
        if (extending) {
            start = row.periodEnd();
        } else if (row.status().equals("TRIALING") && row.trialEndsAt() != null && row.trialEndsAt().isAfter(now)) {
            start = row.trialEndsAt();
        }
        Instant end = start.atZone(DhakaTime.ZONE).plusMonths(months).toInstant();
        jdbc.sql("""
                        update subscriptions set plan_code = ?,
                               status = case when status = 'SUSPENDED' then 'SUSPENDED' else 'ACTIVE' end,
                               suspended_from = case when status = 'SUSPENDED' then 'ACTIVE' else suspended_from end,
                               current_period_start = case when ? then current_period_start else ? end,
                               current_period_end = ?, grace_ends_at = null, read_only_since = null,
                               pending_plan_code = null, updated_at = ?, version = version + 1
                        where business_id = ?
                        """)
                .params(planCode, extending, Timestamp.from(start), Timestamp.from(end), Timestamp.from(now), business)
                .update();
        audit.record(business, null, "subscription.changed", "business", business, "from", row.status(), "to", "ACTIVE",
                "plan", planCode, "until", end);
        return new Period(start, end);
    }

    /** A paid upgrade: the plan changes now; the period (and its end) stays. */
    @Transactional(propagation = Propagation.MANDATORY)
    void upgrade(String planCode) {
        UUID business = TenantScope.current().orElseThrow();
        String from = jdbc.sql("select plan_code from subscriptions where business_id = ? for update").param(business)
                .query(String.class).single();
        jdbc.sql("update subscriptions set plan_code = ?, pending_plan_code = null, updated_at = ?, version = version + 1 where business_id = ?")
                .params(planCode, Timestamp.from(clock.instant()), business).update();
        audit.record(business, null, "subscription.upgraded", "business", business, "from", from, "to", planCode);
    }

    /** Onto Free, now: no period, nothing owed, nothing waiting. */
    @Transactional(propagation = Propagation.MANDATORY)
    void toFree(UUID business, String from, UUID by) {
        jdbc.sql("""
                        update subscriptions set plan_code = ?, status = 'ACTIVE', trial_ends_at = null,
                               current_period_start = null, current_period_end = null, grace_ends_at = null,
                               read_only_since = null, pending_plan_code = null, updated_at = ?, version = version + 1
                        where business_id = ?
                        """)
                .params(FREE, Timestamp.from(clock.instant()), business).update();
        audit.record(business, by, "subscription.changed", "business", business, "from", from, "to", "ACTIVE", "plan", FREE);
    }

    /** The business of the TenantScope's. */
    @Transactional(readOnly = true)
    Subscription current() {
        UUID business = TenantScope.current().orElseThrow();
        Optional<Subscription> found = jdbc.sql("""
                        select plan_code, status, trial_ends_at, current_period_start, current_period_end, pending_plan_code,
                               grace_ends_at
                        from subscriptions where business_id = ?
                        """)
                .param(business)
                .query((rs, n) -> new Subscription(rs.getString(1), rs.getString(2), instant(rs.getTimestamp(3)),
                        instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)), rs.getString(6), instant(rs.getTimestamp(7))))
                .optional();
        return found.orElseThrow(() -> new IllegalStateException("Business " + business + " has no subscription"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
