package com.mojud.billing;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Billing for platform administration (implementation-plan.md 12a): what a business is on and uses,
 * and the actions administrators take on subscriptions and plans. The caller audits each action.
 */
public interface BillingAdministration {

    record SubscriptionSummary(String planCode, String status, @Nullable Instant trialEndsAt, @Nullable Instant currentPeriodEnd) {
    }

    /** {@code limit} null: unlimited. */
    record Usage(long used, @Nullable Integer limit) {
    }

    record InvoiceSummary(UUID id, String number, String kind, String planCode, int months, long amount, String status, String payBy,
            Instant createdAt, @Nullable Instant paidAt) {
    }

    record Overview(String planCode, String status, @Nullable Instant trialEndsAt, @Nullable Instant currentPeriodEnd,
            @Nullable String pendingPlanCode, @Nullable String suspendedFrom, Map<String, Usage> usage, List<InvoiceSummary> invoices) {
    }

    /** {@code limits}: metric key to limit (null unlimited); prices in poisha. */
    record PlanInfo(String code, String nameEn, String nameBn, long priceMonthly, boolean isPublic,
            Map<String, Integer> limits, boolean exports) {
    }

    /**
     * {@code paying}: on a paid plan now (ACTIVE or GRACE); {@code trialsEnded}: businesses whose
     * trial has ended (paying or not), for conversion; {@code mrr}: the paying businesses' monthly
     * prices, in poisha.
     */
    record Metrics(long trialing, long paying, long readOnly, long suspended, long trialsEnded, long everPaid, long mrr,
            long grace, List<PlanCount> plans, long waitingTransfers, long waitingAmount, @Nullable Instant oldestWaiting) {
    }

    /** How many businesses are on one plan. */
    record PlanCount(String code, long businesses) {
    }

    Metrics metrics();

    Map<UUID, SubscriptionSummary> subscriptions(Collection<UUID> businessIds);

    Overview overview(UUID businessId);

    /** Only billing stays open to the business (10b); its status is remembered for {@link #unsuspend}. */
    void suspend(UUID businessId);

    void unsuspend(UUID businessId);

    /** A business still on its trial plan (trialing, or read-only after it) gets {@code days} more of it. */
    void extendTrial(UUID businessId, int days);

    /** Puts the business on a plan until {@code periodEnd}, unpaid (a courtesy or a correction). */
    void setPlan(UUID businessId, String planCode, Instant periodEnd);

    List<PlanInfo> plans();

    /** Names, monthly price, whether it is offered, and limits; the trial is never offered. */
    PlanInfo updatePlan(String code, PlanInfo changes);
}
