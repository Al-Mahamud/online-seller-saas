package com.mojud.tenancy.business;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import com.mojud.tenancy.web.SelectedBusiness;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's businesses ({@code /businesses}, {@code /me/switch-business}) and the
 * selected one ({@code /business}). The selected business always comes from the session, never
 * from the request.
 */
@RestController
class BusinessController {

    private final BusinessService businesses;
    private final DeletionRequests deletions;

    BusinessController(BusinessService businesses, DeletionRequests deletions) {
        this.deletions = deletions;
        this.businesses = businesses;
    }

    record CreateRequest(@NotBlank @Size(max = 120) String name, String phone, @Size(max = 300) String address) {
    }

    /** The caller becomes the owner, and the new business is selected. */
    @PostMapping("/api/v1/businesses")
    @ResponseStatus(HttpStatus.CREATED)
    BusinessResponse create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateRequest body,
            HttpServletRequest request) {
        BusinessResponse created = businesses.create(user.id(), body.name(), body.phone(), body.address());
        SelectedBusiness.set(request, created.id());
        return created;
    }

    record MyBusiness(UUID id, String name, BusinessRole role, boolean selected) {
    }

    /** For the business switcher. */
    @GetMapping("/api/v1/businesses")
    List<MyBusiness> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        Optional<UUID> selected = CurrentBusiness.get().map(CurrentBusiness::businessId);
        return businesses.businessesOf(user.id()).stream()
                .map(b -> new MyBusiness(b.businessId(), b.name(), b.role(), selected.filter(b.businessId()::equals).isPresent()))
                .toList();
    }

    record SwitchRequest(@NotNull UUID businessId) {
    }

    @PostMapping("/api/v1/me/switch-business")
    BusinessResponse switchTo(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody SwitchRequest body,
            HttpServletRequest request) {
        BusinessResponse business = businesses.switchTo(user.id(), body.businessId());
        SelectedBusiness.set(request, business.id());
        return business;
    }

    @GetMapping("/api/v1/business")
    @RequiresRole(BusinessRole.STAFF)
    BusinessResponse current() {
        return businesses.current();
    }

    /** Fields left out stay as they are; an empty phone or address clears it. */
    record UpdateRequest(@Size(min = 1, max = 120) String name, String phone,
            @Size(max = 300) String address) {
    }

    @PatchMapping("/api/v1/business")
    @RequiresRole(BusinessRole.MANAGER)
    BusinessResponse update(@Valid @RequestBody UpdateRequest body) {
        return businesses.update(body.name(), body.phone(), body.address());
    }

    record LogoRequest(@NotNull UUID fileId) {
    }

    /** An uploaded file ({@code POST /uploads}) becomes the logo. */
    @PutMapping("/api/v1/business/logo")
    @RequiresRole(BusinessRole.MANAGER)
    BusinessResponse changeLogo(@Valid @RequestBody LogoRequest body) {
        return businesses.changeLogo(body.fileId());
    }

    /** The uploaded file itself is kept. */
    @DeleteMapping("/api/v1/business/logo")
    @RequiresRole(BusinessRole.MANAGER)
    BusinessResponse removeLogo() {
        return businesses.changeLogo(null);
    }

    record DeletionRequest(@NotBlank @Size(max = 200) String password) {
    }

    record DeletionScheduled(Instant deletionDueAt) {
    }

    /**
     * Deletes the business and all its data in 7 days (the owner's password confirms it); cancel
     * before then with {@code DELETE /business/deletion}. The owner's account stays.
     */
    @PostMapping("/api/v1/business/deletion")
    @RequiresRole(BusinessRole.OWNER)
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeletionScheduled requestDeletion(@Valid @RequestBody DeletionRequest body, HttpServletRequest request) {
        return new DeletionScheduled(deletions.request(body.password(), request.getRemoteAddr()));
    }

    @DeleteMapping("/api/v1/business/deletion")
    @RequiresRole(BusinessRole.OWNER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelDeletion() {
        deletions.cancel();
    }

    @GetMapping("/api/v1/business/settings")
    @RequiresRole(BusinessRole.STAFF)
    SettingsResponse settings() {
        return businesses.settings();
    }

    record SettingsRequest(
            StockCommitPoint stockCommitPoint,
            @Min(0) @Max(100000) Integer lowStockDefault,
            @Pattern(regexp = "[A-Z0-9-]{0,10}", message = "{field.order_prefix_invalid}") String orderNumberPrefix,
            @Size(max = 500) String invoiceFooter) {
    }

    record SearchThemeRequest(@NotNull SearchTheme searchTheme) {
    }

    /** The search panel's colours on every list, for everyone in the business: the owner's choice. */
    @PutMapping("/api/v1/business/search-theme")
    @RequiresRole(BusinessRole.OWNER)
    SettingsResponse changeSearchTheme(@Valid @RequestBody SearchThemeRequest body) {
        return businesses.changeSearchTheme(body.searchTheme());
    }

    /** Fields left out stay as they are. */
    @PatchMapping("/api/v1/business/settings")
    @RequiresRole(BusinessRole.MANAGER)
    SettingsResponse updateSettings(@Valid @RequestBody SettingsRequest body) {
        return businesses.updateSettings(body.stockCommitPoint(), body.lowStockDefault(), body.orderNumberPrefix(),
                body.invoiceFooter());
    }
}
