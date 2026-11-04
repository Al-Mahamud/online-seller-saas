package com.mojud.tenancy.web;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.Permission;
import com.mojud.tenancy.business.RolePermissions;
import java.util.Set;
import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.membership.Membership;
import com.mojud.tenancy.membership.MembershipRepository;
import com.mojud.tenancy.system.MembershipDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts each signed-in request inside its business (backend-plan.md §4):
 * <ol>
 * <li>The business comes from the session, never from the request.
 * <li>The membership is read again, inside that business's TenantScope: a removed member, a
 * changed role or a suspended business counts from the next request.
 * <li>When the session has no usable business (none yet, or the member was removed, or the business
 * was suspended), the business the user used last is selected in the same request; if there is
 * none, the request runs with no business.
 * <li>The rest of the request runs in the business's {@link TenantScope}, so every database
 * connection carries it, and {@link CurrentBusiness} holds the role.
 * </ol>
 * Runs after Spring Security, which has already loaded the signed-in user.
 */
@Component
class TenantFilter extends OncePerRequestFilter {

    private final MembershipRepository memberships;
    private final MembershipDirectory directory;
    private final RolePermissions permissions;

    /**
     * The repositories are resolved on first use: servlet filters are created while the web server
     * starts, earlier than the database needs to be ready (and, in tests, before the embedded
     * database's address is known).
     */
    TenantFilter(@Lazy MembershipRepository memberships, @Lazy MembershipDirectory directory,
            @Lazy RolePermissions permissions) {
        this.memberships = memberships;
        this.directory = directory;
        this.permissions = permissions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<AuthenticatedUser> user = signedInUser();
        if (user.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        UUID userId = user.get().id();
        Optional<Membership> membership = SelectedBusiness.get(request).flatMap(id -> activeMembership(id, userId));
        if (membership.isEmpty()) {
            membership = directory.lastUsedBusiness(userId).flatMap(id -> activeMembership(id, userId));
            membership.ifPresentOrElse(
                    found -> SelectedBusiness.set(request, found.getBusinessId()),
                    () -> SelectedBusiness.clear(request));
        }
        if (membership.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        UUID businessId = membership.get().getBusinessId();
        BusinessRole role = membership.get().getRole();
        // What this role may do here is read afresh too, so the owner's change counts at once.
        Set<Permission> allowed = TenantScope.call(businessId, () -> permissions.forRole(role));
        request.setAttribute(CurrentBusiness.class.getName(), new CurrentBusiness(businessId, userId, role, allowed));
        try (TenantScope.Opened ignored = TenantScope.open(businessId)) {
            chain.doFilter(request, response);
        }
    }

    private Optional<Membership> activeMembership(UUID businessId, UUID userId) {
        return TenantScope.call(businessId, () -> memberships.findActive(userId));
    }

    private static Optional<AuthenticatedUser> signedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? Optional.of(user)
                : Optional.empty();
    }
}
