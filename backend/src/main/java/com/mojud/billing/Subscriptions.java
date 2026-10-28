package com.mojud.billing;

import com.mojud.identity.UserDirectory;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessCreated;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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
 * Each business's subscription (implementation-plan.md 10a). A new business starts a 14-day Pro
 * trial, unless its owner already had one (one per owner phone, or account without a phone): then
 * it starts READ_ONLY, to be paid for (enforced from 10b).
 */
@Component
class Subscriptions {

    static final Duration TRIAL = Duration.ofDays(14);

    record Subscription(String planCode, String status, Instant trialEndsAt, Instant currentPeriodEnd,
            String pendingPlanCode) {
    }

    private final JdbcClient jdbc;
    private final UserDirectory users;
    private final AuditTrail audit;
    private final Clock clock;

    Subscriptions(DataSource dataSource, UserDirectory users, AuditTrail audit, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(BusinessCreated event) {
        Instant now = clock.instant();
        // Only a verified phone: an unverified one could be someone else's, whose trial it would use up.
        String claim = users.verifiedPhone(event.ownerId()).map(phone -> "phone:" + phone)
                .orElse("user:" + event.ownerId());
        boolean trial = jdbc.sql("""
                        insert into trial_claims (claim_key, business_id, claimed_at) values (?, ?, ?)
                        on conflict (claim_key) do nothing
                        """)
                .params(claim, event.businessId(), Timestamp.from(now))
                .update() == 1;
        jdbc.sql("""
                        insert into subscriptions (business_id, plan_code, status, trial_ends_at, read_only_since, created_at,
                                                   updated_at, version)
                        values (?, 'TRIAL', ?, ?, ?, ?, ?, 0)
                        """)
                .params(event.businessId(), trial ? "TRIALING" : "READ_ONLY", Timestamp.from(trial ? now.plus(TRIAL) : now),
                        trial ? null : Timestamp.from(now), Timestamp.from(now), Timestamp.from(now))
                .update();
    }

    /**
     * A plan change asked for by the owner. A cheaper plan waits for the renewal (pending); the
     * current plan cancels a waiting change; anything that needs paying (a dearer plan, or any plan
     * from a trial or read-only) waits for payments (Phase 11).
     */
    @Transactional
    void choose(Plans.Plan target, Plans.Plan current, UUID ownerId) {
        UUID business = TenantScope.current().orElseThrow();
        String status = jdbc.sql("select status from subscriptions where business_id = ? for update")
                .param(business).query(String.class).single();
        boolean paid = status.equals("ACTIVE") || status.equals("GRACE");
        if (!paid || target.priceMonthly() > current.priceMonthly()) {
            throw new ApiException(ErrorCode.BILLING_PAYMENT_NEEDED);
        }
        String pending = target.code().equals(current.code()) ? null : target.code();
        jdbc.sql("update subscriptions set pending_plan_code = ?, updated_at = ? where business_id = ?")
                .params(pending, Timestamp.from(clock.instant()), business).update();
        audit.record(business, ownerId, "subscription.plan_scheduled", "business", business, "plan", pending);
    }

    /** The business of the TenantScope's. */
    @Transactional(readOnly = true)
    Subscription current() {
        UUID business = TenantScope.current().orElseThrow();
        Optional<Subscription> found = jdbc.sql("""
                        select plan_code, status, trial_ends_at, current_period_end, pending_plan_code
                        from subscriptions where business_id = ?
                        """)
                .param(business)
                .query((rs, n) -> new Subscription(rs.getString(1), rs.getString(2), instant(rs.getTimestamp(3)),
                        instant(rs.getTimestamp(4)), rs.getString(5)))
                .optional();
        return found.orElseThrow(() -> new IllegalStateException("Business " + business + " has no subscription"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
