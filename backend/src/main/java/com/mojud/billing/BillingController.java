package com.mojud.billing;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import com.mojud.tenancy.TeamSize;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The business's plan and usage (managers and owners), and the plans on offer (anyone signed in). */
@RestController
class BillingController {

    /** {@code limit} null: unlimited. */
    record Usage(long used, Integer limit) {
    }

    /** {@code priceMonthly} in poisha; {@code limits}: metric key to limit (null unlimited). */
    record PlanView(String code, String nameEn, String nameBn, long priceMonthly, Map<String, Integer> limits,
            boolean exports) {

        static PlanView of(Plans.Plan plan) {
            return new PlanView(plan.code(), plan.nameEn(), plan.nameBn(), plan.priceMonthly(), plan.limits(), plan.exports());
        }
    }

    record Billing(PlanView plan, String status, Instant trialEndsAt, Instant currentPeriodEnd, String pendingPlanCode,
            Map<String, Usage> usage) {
    }

    private final Subscriptions subscriptions;
    private final Plans plans;
    private final Entitlements entitlements;
    private final List<UsageSource> sources;
    private final TeamSize team;

    BillingController(Subscriptions subscriptions, Plans plans, Entitlements entitlements, List<UsageSource> sources,
            TeamSize team) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.entitlements = entitlements;
        this.sources = sources;
        this.team = team;
    }

    @GetMapping("/api/v1/billing")
    @RequiresRole(BusinessRole.MANAGER)
    @Transactional(readOnly = true)
    Billing billing() {
        Subscriptions.Subscription subscription = subscriptions.current();
        Plans.Plan plan = plans.get(subscription.planCode());
        Map<String, Usage> usage = new LinkedHashMap<>();
        long variants = sources.stream().filter(s -> s.metric() == Metric.VARIANTS).mapToLong(UsageSource::used).sum();
        usage.put(Metric.VARIANTS.key(), new Usage(variants, plan.limit(Metric.VARIANTS)));
        usage.put(Metric.ORDERS_PER_MONTH.key(), new Usage(entitlements.used(Metric.ORDERS_PER_MONTH),
                plan.limit(Metric.ORDERS_PER_MONTH)));
        usage.put(Metric.STAFF_SEATS.key(), new Usage(team.staffSeatsUsed(), plan.limit(Metric.STAFF_SEATS)));
        return new Billing(PlanView.of(plan), subscription.status(), subscription.trialEndsAt(),
                subscription.currentPeriodEnd(), subscription.pendingPlanCode(), usage);
    }

    /** The plans a business can choose (not the trial). */
    @GetMapping("/api/v1/plans")
    List<PlanView> plans() {
        return plans.all().stream().filter(Plans.Plan::isPublic).map(PlanView::of).toList();
    }
}
