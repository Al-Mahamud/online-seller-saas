package com.mojud.admin;

import com.mojud.audit.AdminAudit;
import com.mojud.billing.BillingAdministration;
import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.error.ApiException;
import com.mojud.tenancy.BusinessDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Businesses, subscriptions and plans for platform administrators (implementation-plan.md 12a;
 * only PLATFORM_ADMIN reaches /api/v1/admin/**). Composes what tenancy and billing publish for
 * administration; every action is recorded in the admin audit log once it succeeded.
 */
@RestController
class AdminBusinessController {

    record Row(BusinessDirectory.BusinessSummary business, BillingAdministration.SubscriptionSummary subscription) {
    }

    record Detail(BusinessDirectory.BusinessSummary business, BillingAdministration.Overview billing,
            List<AdminAudit.Entry> adminActions) {
    }

    private final BusinessDirectory businesses;
    private final BillingAdministration billing;
    private final AdminAudit audit;

    AdminBusinessController(BusinessDirectory businesses, BillingAdministration billing, AdminAudit audit) {
        this.businesses = businesses;
        this.billing = billing;
        this.audit = audit;
    }

    /** {@code q}: part of the name, or the owner's phone or email; newest first, up to {@code limit} (100). */
    @GetMapping("/api/v1/admin/businesses")
    List<Row> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "50") int limit) {
        List<BusinessDirectory.BusinessSummary> found = businesses.search(q, Math.max(1, Math.min(limit, 100)));
        Map<UUID, BillingAdministration.SubscriptionSummary> subscriptions = billing.subscriptions(
                found.stream().map(BusinessDirectory.BusinessSummary::id).toList());
        return found.stream().map(business -> new Row(business, subscriptions.get(business.id()))).toList();
    }

    @GetMapping("/api/v1/admin/businesses/{id}")
    Detail get(@PathVariable UUID id) {
        BusinessDirectory.BusinessSummary business = businesses.find(id).orElseThrow(ApiException::notFound);
        return new Detail(business, billing.overview(id), audit.recent(id, 20));
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** Only billing stays open to the business; the reason is kept in the admin audit log. */
    @PostMapping("/api/v1/admin/businesses/{id}/suspend")
    Detail suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body, @AuthenticationPrincipal AuthenticatedUser admin) {
        exists(id);
        billing.suspend(id);
        audit.record(admin.id(), "business.suspended", "business", id.toString(), id, "reason", body.reason().strip());
        return get(id);
    }

    @PostMapping("/api/v1/admin/businesses/{id}/unsuspend")
    Detail unsuspend(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser admin) {
        exists(id);
        billing.unsuspend(id);
        audit.record(admin.id(), "business.unsuspended", "business", id.toString(), id);
        return get(id);
    }

    record TrialRequest(@NotNull @Min(1) @Max(90) Integer days) {
    }

    @PostMapping("/api/v1/admin/businesses/{id}/extend-trial")
    Detail extendTrial(@PathVariable UUID id, @Valid @RequestBody TrialRequest body, @AuthenticationPrincipal AuthenticatedUser admin) {
        exists(id);
        billing.extendTrial(id, body.days());
        audit.record(admin.id(), "trial.extended", "business", id.toString(), id, "days", body.days());
        return get(id);
    }

    record PlanRequest(@NotBlank String planCode, @NotNull Instant periodEnd) {
    }

    /** A plan until {@code periodEnd}, without payment: a courtesy or a correction. */
    @PutMapping("/api/v1/admin/businesses/{id}/plan")
    Detail setPlan(@PathVariable UUID id, @Valid @RequestBody PlanRequest body, @AuthenticationPrincipal AuthenticatedUser admin) {
        exists(id);
        billing.setPlan(id, body.planCode(), body.periodEnd());
        audit.record(admin.id(), "plan.set", "business", id.toString(), id, "plan", body.planCode(), "until", body.periodEnd());
        return get(id);
    }

    @GetMapping("/api/v1/admin/plans")
    List<BillingAdministration.PlanInfo> plans() {
        return billing.plans();
    }

    /** Names, monthly price (poisha), whether offered, limits (null: unlimited) and exports. */
    record PlanChanges(@Size(max = 50) String nameEn, @Size(max = 50) String nameBn, @NotNull @Min(0) Long priceMonthly,
            @NotNull Boolean isPublic, Map<String, Integer> limits, @NotNull Boolean exports) {
    }

    @PutMapping("/api/v1/admin/plans/{code}")
    BillingAdministration.PlanInfo updatePlan(@PathVariable String code, @Valid @RequestBody PlanChanges body,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        if (billing.plans().stream().noneMatch(plan -> plan.code().equals(code))) {
            throw ApiException.notFound();
        }
        BillingAdministration.PlanInfo updated = billing.updatePlan(code, new BillingAdministration.PlanInfo(code,
                body.nameEn(), body.nameBn(), body.priceMonthly(), body.isPublic(), body.limits(), body.exports()));
        audit.record(admin.id(), "plan.edited", "plan", code, null, "price", updated.priceMonthly(), "public", updated.isPublic(),
                "limits", updated.limits(), "exports", updated.exports());
        return updated;
    }

    /** Recent admin actions, newest first; {@code businessId} narrows them to one business. */
    @GetMapping("/api/v1/admin/audit")
    List<AdminAudit.Entry> auditLog(@RequestParam(required = false) UUID businessId,
            @RequestParam(defaultValue = "100") int limit) {
        return audit.recent(businessId, Math.max(1, Math.min(limit, 500)));
    }

    private void exists(UUID id) {
        businesses.find(id).orElseThrow(ApiException::notFound);
    }
}
