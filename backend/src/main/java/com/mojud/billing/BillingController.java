package com.mojud.billing;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import com.mojud.tenancy.RequiresPermission;
import com.mojud.tenancy.Permission;
import com.mojud.tenancy.TeamSize;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import com.mojud.tenancy.BusinessProfile;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The business's plan and usage (managers and owners), and the plans on offer (anyone signed in). */
@RestController
class BillingController {

    /** {@code limit} null: unlimited. */
    record Usage(long used, @Nullable Integer limit) {
    }

    /** {@code priceMonthly} in poisha; {@code limits}: metric key to limit (null unlimited). */
    record PlanView(String code, String nameEn, String nameBn, long priceMonthly, Map<String, Integer> limits,
            boolean exports) {

        static PlanView of(Plans.Plan plan) {
            return new PlanView(plan.code(), plan.nameEn(), plan.nameBn(), plan.priceMonthly(), plan.limits(), plan.exports());
        }
    }

    record Billing(PlanView plan, String status, @Nullable Instant trialEndsAt, @Nullable Instant currentPeriodEnd,
            @Nullable Instant graceEndsAt, @Nullable String pendingPlanCode, Map<String, Usage> usage) {
    }

    /** What every member's screen needs to know: the banners. */
    record Status(String status, @Nullable Instant trialEndsAt, @Nullable Instant currentPeriodEnd,
            @Nullable Instant graceEndsAt) {
    }

    private final Subscriptions subscriptions;
    private final Plans plans;
    private final Entitlements entitlements;
    private final List<UsageSource> sources;
    private final TeamSize team;
    private final Invoices invoices;
    private final InvoicePages pages;
    private final BusinessProfile profiles;

    BillingController(Subscriptions subscriptions, Plans plans, Entitlements entitlements, List<UsageSource> sources,
            TeamSize team, Invoices invoices, InvoicePages pages, BusinessProfile profiles) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.entitlements = entitlements;
        this.sources = sources;
        this.team = team;
        this.invoices = invoices;
        this.pages = pages;
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/billing")
    @RequiresRole(BusinessRole.STAFF)
    @RequiresPermission(Permission.SETTINGS)
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
                subscription.currentPeriodEnd(), subscription.graceEndsAt(), subscription.pendingPlanCode(), usage);
    }

    @GetMapping("/api/v1/billing/status")
    @RequiresRole(BusinessRole.STAFF)
    @Transactional(readOnly = true)
    Status status() {
        Subscriptions.Subscription subscription = subscriptions.current();
        return new Status(subscription.status(), subscription.trialEndsAt(), subscription.currentPeriodEnd(),
                subscription.graceEndsAt());
    }

    /**
     * What a payment would come to, as checkout or upgrade would charge it: {@code months} (1, 6 or
     * 12) of a plan, or without them, an upgrade for the rest of the running period, prorated.
     */
    @GetMapping("/api/v1/billing/quote")
    @RequiresRole(BusinessRole.OWNER)
    Invoices.Quote quote(@RequestParam String planCode, @RequestParam(required = false) Integer months) {
        return invoices.quote(planCode, months);
    }

    record ChooseRequest(@NotNull String planCode) {
    }

    /**
     * The owner picks a plan: a cheaper one from the next renewal, or back to the current one.
     * Paying for a plan (upgrades, ending a trial) comes with payments (Phase 11): 409 for now.
     */
    @PutMapping("/api/v1/billing/plan")
    @RequiresRole(BusinessRole.OWNER)
    Billing choose(@Valid @RequestBody ChooseRequest body) {
        Plans.Plan target = plans.all().stream().filter(p -> p.isPublic() && p.code().equals(body.planCode())).findFirst()
                .orElseThrow(() -> ApiException.invalidField("planCode", "field.invalid_value"));
        subscriptions.choose(target, plans.get(subscriptions.current().planCode()), CurrentBusiness.require().userId());
        return billing();
    }

    /** {@code payBy}: SSLCOMMERZ (default) or BANK_TRANSFER (paid when an admin sees it arrive). */
    record CheckoutRequest(@NotNull String planCode, @NotNull Integer months, Invoices.PayBy payBy) {
    }

    /**
     * Pays for {@code months} (1, 6 or 12) of a plan through SSLCommerz: returns the gateway's page
     * to send the browser to. The invoice is paid only when the gateway confirms it; meanwhile the
     * web app polls {@code GET /billing/invoices/{id}}.
     */
    @PostMapping("/api/v1/billing/checkout")
    @RequiresRole(BusinessRole.OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    Invoices.Checkout checkout(@Valid @RequestBody CheckoutRequest body) {
        return invoices.checkout(body.planCode(), body.months(), body.payBy() == null ? Invoices.PayBy.SSLCOMMERZ : body.payBy());
    }

    record UpgradeRequest(@NotNull String planCode, Invoices.PayBy payBy) {
    }

    /** A dearer plan for the rest of the running period, prorated; the plan changes when paid. */
    @PostMapping("/api/v1/billing/upgrade")
    @RequiresRole(BusinessRole.OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    Invoices.Checkout upgrade(@Valid @RequestBody UpgradeRequest body) {
        return invoices.upgrade(body.planCode(), body.payBy() == null ? Invoices.PayBy.SSLCOMMERZ : body.payBy());
    }

    @GetMapping("/api/v1/billing/invoices")
    @RequiresRole(BusinessRole.STAFF)
    @RequiresPermission(Permission.SETTINGS)
    @Transactional(readOnly = true)
    PagedResult<Invoices.InvoiceView> invoices(@ParameterObject PageQuery paging) {
        return invoices.list(paging);
    }

    /** The invoice as a page to print or save as PDF; {@code lang} bn or en (default: the reader's). */
    @GetMapping("/api/v1/billing/invoices/{id}/print")
    @RequiresRole(BusinessRole.STAFF)
    @RequiresPermission(Permission.SETTINGS)
    @Transactional(readOnly = true)
    ResponseEntity<String> print(@PathVariable UUID id, @RequestParam(required = false) String lang) {
        boolean bangla = lang != null && !lang.isBlank() ? lang.equalsIgnoreCase("bn")
                : LocaleContextHolder.getLocale().getLanguage().equals("bn");
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", InvoicePages.CONTENT_SECURITY_POLICY)
                .body(pages.render(invoices.get(id), profiles.current(), bangla));
    }

    @GetMapping("/api/v1/billing/invoices/{id}")
    @RequiresRole(BusinessRole.STAFF)
    @RequiresPermission(Permission.SETTINGS)
    @Transactional(readOnly = true)
    Invoices.InvoiceView invoice(@PathVariable UUID id) {
        return invoices.get(id);
    }

    /** The plans a business can choose (not the trial). */
    @GetMapping("/api/v1/plans")
    List<PlanView> plans() {
        return plans.all().stream().filter(Plans.Plan::isPublic).map(PlanView::of).toList();
    }
}
