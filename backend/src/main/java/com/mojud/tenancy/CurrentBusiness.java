package com.mojud.tenancy;

import com.mojud.shared.tenant.TenantScope;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The business the current request works in, and the signed-in user's role there. Set for each
 * request by the tenancy module from the session and a fresh membership check, so a removed member
 * or a changed role takes effect on the next request.
 *
 * <p>It is only ever returned while the request works in that business: code that opens another
 * business's {@code TenantScope} (creating a business, switching) sees none, rather than a role
 * that belongs to a different business.
 */
public record CurrentBusiness(UUID businessId, UUID userId, BusinessRole role, Set<Permission> permissions) {

    public CurrentBusiness {
        permissions = role == BusinessRole.OWNER ? Permission.all() : Set.copyOf(permissions);
    }

    /** With the role's default permissions, as before a business changes them. */
    public CurrentBusiness(UUID businessId, UUID userId, BusinessRole role) {
        this(businessId, userId, role, Permission.defaults(role));
    }

    /** Empty outside a request, before sign-in, or when no business is selected. */
    public static Optional<CurrentBusiness> get() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return Optional.empty();
        }
        Optional<UUID> scope = TenantScope.current();
        return Optional.ofNullable((CurrentBusiness) request.getAttribute(CurrentBusiness.class.getName(), RequestAttributes.SCOPE_REQUEST))
                .filter(business -> scope.filter(business.businessId()::equals).isPresent());
    }

    /**
     * For code behind {@link RequiresRole}, where a business is always selected.
     *
     * @throws IllegalStateException otherwise
     */
    public static CurrentBusiness require() {
        return get().orElseThrow(() -> new IllegalStateException("No business selected; is @RequiresRole missing?"));
    }

    public boolean isAtLeast(BusinessRole required) {
        return role.atLeast(required);
    }

    /** The owner always; others as this business allows their role. */
    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
