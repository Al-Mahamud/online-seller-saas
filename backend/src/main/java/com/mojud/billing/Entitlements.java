package com.mojud.billing;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.time.DhakaTime;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the business's plan allows (backend-plan.md §8), checked in the transaction of the change.
 * Over a limit the answer is 402 {@code plan.limit_reached} with the metric and the limit; data
 * already there stays readable. A trial has Pro's limits.
 */
@Component
public class Entitlements {

    private final Subscriptions subscriptions;
    private final Plans plans;
    private final JdbcClient jdbc;
    private final Clock clock;

    Entitlements(Subscriptions subscriptions, Plans plans, DataSource dataSource, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = clock;
    }

    /** The limit, empty when unlimited. */
    @Transactional(readOnly = true)
    public Optional<Integer> limit(Metric metric) {
        return Optional.ofNullable(plan().limit(metric));
    }

    /**
     * Refuses a change that would leave {@code total} of {@code metric}. The caller counts, under a
     * lock that keeps two changes from counting at once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWithin(Metric metric, long total) {
        Integer limit = plan().limit(metric);
        if (limit != null && total > limit) {
            throw limitReached(metric, limit);
        }
    }

    /**
     * Counts one more of a monthly metric, or refuses at the limit. A single conditional UPDATE, so
     * two at once at the limit cannot both pass; a rolled-back change gives its count back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Metric metric) {
        UUID business = TenantScope.current().orElseThrow();
        String period = DhakaTime.currentMonth(clock).toString();
        Integer limit = plan().limit(metric);
        jdbc.sql("""
                        insert into usage_counters (business_id, metric, period, used) values (?, ?, ?, 0)
                        on conflict (business_id, metric, period) do nothing
                        """)
                .params(business, metric.key(), period)
                .update();
        int counted = jdbc.sql("""
                        update usage_counters set used = used + 1
                        where business_id = ? and metric = ? and period = ? and (? or used < ?)
                        """)
                .params(business, metric.key(), period, limit == null, limit == null ? 0 : limit)
                .update();
        if (counted == 0) {
            throw limitReached(metric, limit);
        }
    }

    /** This month's count of a monthly metric. */
    @Transactional(readOnly = true)
    public long used(Metric metric) {
        return jdbc.sql("select coalesce(max(used), 0) from usage_counters where business_id = ? and metric = ? and period = ?")
                .params(TenantScope.current().orElseThrow(), metric.key(), DhakaTime.currentMonth(clock).toString())
                .query(Long.class)
                .single();
    }

    /** CSV exports are a Pro feature (and the trial's). */
    @Transactional(readOnly = true)
    public void requireExports() {
        if (!plan().exports()) {
            throw new ApiException(ErrorCode.PLAN_LIMIT_REACHED).withDetail("metric", "exports");
        }
    }

    Plans.Plan plan() {
        return plans.get(subscriptions.current().planCode());
    }

    private static ApiException limitReached(Metric metric, Integer limit) {
        return new ApiException(ErrorCode.PLAN_LIMIT_REACHED).withDetail("metric", metric.key()).withDetail("limit", limit);
    }
}
