package com.mojud.tenancy.web;

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
 * <li>The business comes from the session, never from the request; a session without one gets the
 * business the user used last.
 * <li>The membership is read again, inside that business's TenantScope: a removed member or a
 * changed role counts from the next request. Without an active membership the selection is
 * dropped and the request runs with no business.
 * <li>The rest of the request runs in the business's {@link TenantScope}, so every database
 * connection carries it, and {@link CurrentBusiness} holds the role.
 * </ol>
 * Runs after Spring Security, which has already loaded the signed-in user.
 */
@Component
class TenantFilter extends OncePerRequestFilter {

    private final MembershipRepository memberships;
    private final MembershipDirectory directory;

    /**
     * The repositories are resolved on first use: servlet filters are created while the web server
     * starts, earlier than the database needs to be ready (and, in tests, before the embedded
     * database's address is known).
     */
    TenantFilter(@Lazy MembershipRepository memberships, @Lazy MembershipDirectory directory) {
        this.memberships = memberships;
        this.directory = directory;
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
        Optional<UUID> selected = SelectedBusiness.get(request).or(() -> {
            Optional<UUID> lastUsed = directory.lastUsedBusiness(userId);
            lastUsed.ifPresent(businessId -> SelectedBusiness.set(request, businessId));
            return lastUsed;
        });
        Optional<Membership> membership = selected.flatMap(businessId -> TenantScope.call(businessId,
                () -> memberships.findByUserId(userId).filter(Membership::isActive)));
        if (membership.isEmpty()) {
            selected.ifPresent(dropped -> SelectedBusiness.clear(request));
            chain.doFilter(request, response);
            return;
        }
        UUID businessId = membership.get().getBusinessId();
        request.setAttribute(CurrentBusiness.class.getName(),
                new CurrentBusiness(businessId, userId, membership.get().getRole()));
        try {
            TenantScope.call(businessId, () -> {
                doFilterUnchecked(chain, request, response);
                return null;
            });
        } catch (FilterFailure failure) {
            failure.rethrow();
        }
    }

    private static Optional<AuthenticatedUser> signedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? Optional.of(user)
                : Optional.empty();
    }

    private static void doFilterUnchecked(FilterChain chain, HttpServletRequest request, HttpServletResponse response) {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            throw new FilterFailure(e);
        }
    }

    /** Carries a checked exception out of the TenantScope lambda. */
    private static final class FilterFailure extends RuntimeException {

        FilterFailure(Exception cause) {
            super(cause);
        }

        void rethrow() throws IOException, ServletException {
            if (getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) getCause();
        }
    }
}
