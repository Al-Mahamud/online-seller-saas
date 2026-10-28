package com.mojud.billing;

import com.mojud.billing.system.BillingSystemQueries;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.TeamSize;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link BillingAdministration}: one business's figures and changes as that business (its
 * TenantScope, the app role, its row locked); what spans businesses or plans through the system role.
 */
@Component
class BillingAdmin implements BillingAdministration {

    static final int MAX_TRIAL_DAYS = 90;

    private final Plans plans;
    private final Subscriptions subscriptions;
    private final Entitlements entitlements;
    private final List<UsageSource> sources;
    private final TeamSize team;
    private final BillingSystemQueries system;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;

    BillingAdmin(Plans plans, Subscriptions subscriptions, Entitlements entitlements, List<UsageSource> sources,
            TeamSize team, BillingSystemQueries system, DataSource dataSource, PlatformTransactionManager transactionManager,
            JsonMapper json, Clock clock) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.sources = sources;
        this.team = team;
        this.system = system;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Map<UUID, SubscriptionSummary> subscriptions(Collection<UUID> businessIds) {
        return system.subscriptions(businessIds).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> new SubscriptionSummary(e.getValue().planCode(), e.getValue().status(), e.getValue().trialEndsAt(),
                        e.getValue().currentPeriodEnd())));
    }

    @Override
    public Overview overview(UUID businessId) {
        return TenantScope.call(businessId, () -> transactions.execute(status -> {
            Subscriptions.Subscription subscription = subscriptions.current();
            Plans.Plan plan = plans.get(subscription.planCode());
            Map<String, Usage> usage = new LinkedHashMap<>();
            usage.put(Metric.VARIANTS.key(), new Usage(sources.stream().filter(s -> s.metric() == Metric.VARIANTS)
                    .mapToLong(UsageSource::used).sum(), plan.limit(Metric.VARIANTS)));
            usage.put(Metric.ORDERS_PER_MONTH.key(), new Usage(entitlements.used(Metric.ORDERS_PER_MONTH),
                    plan.limit(Metric.ORDERS_PER_MONTH)));
            usage.put(Metric.STAFF_SEATS.key(), new Usage(team.staffSeatsUsed(), plan.limit(Metric.STAFF_SEATS)));
            List<InvoiceSummary> invoices = jdbc.sql("""
                            select id, number, kind, plan_code, amount, status, pay_by, created_at, paid_at from invoices
                            where business_id = ? order by created_at desc limit 20
                            """)
                    .param(businessId)
                    .query((rs, n) -> new InvoiceSummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                            rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()))
                    .list();
            String suspendedFrom = jdbc.sql("select suspended_from from subscriptions where business_id = ?").param(businessId)
                    .query(String.class).optional().orElse(null);
            return new Overview(subscription.planCode(), subscription.status(), subscription.trialEndsAt(),
                    subscription.currentPeriodEnd(), subscription.pendingPlanCode(), suspendedFrom, usage, invoices);
        }));
    }

    @Override
    public void suspend(UUID businessId) {
        change(businessId, status -> {
            if (status.equals("SUSPENDED")) {
                throw new ApiException(ErrorCode.ADMIN_NO_CHANGE);
            }
            return jdbc.sql("update subscriptions set suspended_from = status, status = 'SUSPENDED', updated_at = ? where business_id = ?")
                    .params(now(), businessId);
        });
    }

    @Override
    public void unsuspend(UUID businessId) {
        change(businessId, status -> {
            if (!status.equals("SUSPENDED")) {
                throw new ApiException(ErrorCode.ADMIN_NO_CHANGE);
            }
            return jdbc.sql("update subscriptions set status = suspended_from, suspended_from = null, updated_at = ? where business_id = ?")
                    .params(now(), businessId);
        });
    }

    @Override
    public void extendTrial(UUID businessId, int days) {
        if (days < 1 || days > MAX_TRIAL_DAYS) {
            throw ApiException.invalidField("days", "field.invalid_value");
        }
        change(businessId, status -> {
            String plan = jdbc.sql("select plan_code from subscriptions where business_id = ?").param(businessId)
                    .query(String.class).single();
            if (!plan.equals("TRIAL") || !(status.equals("TRIALING") || status.equals("READ_ONLY"))) {
                throw new ApiException(ErrorCode.BILLING_NOT_ON_TRIAL);
            }
            Instant now = clock.instant();
            return jdbc.sql("""
                            update subscriptions set status = 'TRIALING', read_only_since = null, updated_at = ?,
                                   trial_ends_at = greatest(coalesce(trial_ends_at, ?), ?) + make_interval(days => ?)
                            where business_id = ?
                            """)
                    .params(Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), days, businessId);
        });
    }

    @Override
    public void setPlan(UUID businessId, String planCode, Instant periodEnd) {
        Plans.Plan plan = plans.all().stream().filter(p -> p.code().equals(planCode) && !p.code().equals("TRIAL")).findFirst()
                .orElseThrow(() -> ApiException.invalidField("planCode", "field.invalid_value"));
        if (periodEnd == null || !periodEnd.isAfter(clock.instant()) || periodEnd.isAfter(clock.instant().plus(Duration.ofDays(800)))) {
            throw ApiException.invalidField("periodEnd", "field.invalid_value");
        }
        change(businessId, status -> jdbc.sql("""
                        update subscriptions set plan_code = ?, status = 'ACTIVE', suspended_from = null, read_only_since = null,
                               grace_ends_at = null, pending_plan_code = null,
                               current_period_start = coalesce(current_period_start, ?), current_period_end = ?, updated_at = ?
                        where business_id = ?
                        """)
                .params(plan.code(), now(), Timestamp.from(periodEnd), now(), businessId));
    }

    @Override
    public List<PlanInfo> plans() {
        return plans.all().stream().map(BillingAdmin::info).toList();
    }

    @Override
    public PlanInfo updatePlan(String code, PlanInfo changes) {
        Plans.Plan current = plans.get(code);
        boolean isPublic = changes.isPublic() && !code.equals("TRIAL");
        if (changes.priceMonthly() < 0 || (isPublic && changes.priceMonthly() == 0)) {
            throw ApiException.invalidField("priceMonthly", "field.invalid_value");
        }
        Map<String, Object> limits = new LinkedHashMap<>();
        for (Metric metric : Metric.values()) {
            Integer limit = changes.limits() == null ? current.limit(metric) : changes.limits().get(metric.key());
            if (limit != null && limit < 0) {
                throw ApiException.invalidField("limits", "field.invalid_value");
            }
            limits.put(metric.key(), limit);
        }
        limits.put("exports", changes.exports());
        system.updatePlan(code, blankOr(changes.nameEn(), current.nameEn()), blankOr(changes.nameBn(), current.nameBn()),
                changes.priceMonthly(), isPublic, json.writeValueAsString(limits));
        plans.reload();
        return info(plans.get(code));
    }

    /** Runs a change on one business's subscription, as that business, with its row locked. */
    private void change(UUID businessId, Function<String, JdbcClient.StatementSpec> statement) {
        TenantScope.run(businessId, () -> transactions.executeWithoutResult(tx -> {
            String status = jdbc.sql("select status from subscriptions where business_id = ? for update").param(businessId)
                    .query(String.class).optional().orElseThrow(ApiException::notFound);
            statement.apply(status).update();
        }));
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static String blankOr(String value, String otherwise) {
        return value == null || value.isBlank() ? otherwise : value.strip();
    }

    private static PlanInfo info(Plans.Plan plan) {
        return new PlanInfo(plan.code(), plan.nameEn(), plan.nameBn(), plan.priceMonthly(), plan.isPublic(), plan.limits(),
                plan.exports());
    }
}
