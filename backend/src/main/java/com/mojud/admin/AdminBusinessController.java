package com.mojud.admin;

import com.mojud.audit.AdminAudit;
import com.mojud.billing.BillingAdministration;
import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
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
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
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

    /** The subscription states a business can be narrowed to. */
    enum State { ACTIVE, GRACE, READ_ONLY, SUSPENDED, TRIALING }

    /**
     * {@code q}: part of the name, or the owner's phone, email or User ID; {@code plan}: a plan's
     * code; {@code state}: the subscription's state. Newest first.
     */
    @GetMapping("/api/v1/admin/businesses")
    PagedResult<Row> search(@RequestParam(required = false) String q, @RequestParam(required = false) String plan,
            @RequestParam(required = false) State state, @ParameterObject PageQuery paging) {
        if (plan != null && !plan.isBlank() && billing.plans().stream().noneMatch(p -> p.code().equals(plan))) {
            throw ApiException.invalidField("plan", "field.invalid_value");
        }
        BusinessDirectory.Narrowing narrowing = new BusinessDirectory.Narrowing(plan == null || plan.isBlank() ? null : plan,
                state == null ? null : state.name());
        return businesses.search(q, narrowing, paging).mapItems(found -> {
            Map<UUID, BillingAdministration.SubscriptionSummary> subscriptions = billing.subscriptions(
                    found.stream().map(BusinessDirectory.BusinessSummary::id).toList());
            return found.stream().map(business -> new Row(business, subscriptions.get(business.id()))).toList();
        });
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

    /** {@code reason}: why, for the admin log; optional. */
    record PlanRequest(@NotBlank String planCode, @NotNull Instant periodEnd, @Size(max = 500) @Nullable String reason) {
    }

    /** A plan until {@code periodEnd}, without payment: a courtesy or a correction. */
    @PutMapping("/api/v1/admin/businesses/{id}/plan")
    Detail setPlan(@PathVariable UUID id, @Valid @RequestBody PlanRequest body, @AuthenticationPrincipal AuthenticatedUser admin) {
        exists(id);
        billing.setPlan(id, body.planCode(), body.periodEnd());
        String reason = body.reason() == null ? "" : body.reason().strip();
        if (reason.isEmpty()) {
            audit.record(admin.id(), "plan.set", "business", id.toString(), id, "plan", body.planCode(), "until", body.periodEnd());
        } else {
            audit.record(admin.id(), "plan.set", "business", id.toString(), id, "plan", body.planCode(), "until", body.periodEnd(),
                    "reason", reason);
        }
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

    /**
     * Recent admin actions, newest first; {@code businessId} narrows them to one business,
     * {@code action} (repeatable) to those actions, such as {@code invoice.marked_paid}.
     */
    @GetMapping("/api/v1/admin/audit")
    PagedResult<AdminAudit.Entry> auditLog(@RequestParam(required = false) UUID businessId,
            @RequestParam(required = false) List<String> action, @ParameterObject PageQuery paging) {
        List<String> actions = action == null ? List.of() : action.stream().filter(a -> !a.isBlank()).map(String::strip).toList();
        if (actions.size() > 10 || actions.stream().anyMatch(a -> a.length() > 60)) {
            throw ApiException.invalidField("action", "field.invalid_value");
        }
        return audit.page(businessId, actions, paging);
    }

    private void exists(UUID id) {
        businesses.find(id).orElseThrow(ApiException::notFound);
    }
}
