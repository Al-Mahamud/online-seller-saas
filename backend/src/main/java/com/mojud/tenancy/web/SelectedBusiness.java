package com.mojud.tenancy.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;

/**
 * The business chosen in this session. Only an id: the role is read again on every request
 * ({@link TenantFilter}), so it is never stale.
 */
public final class SelectedBusiness {

    private static final String SESSION_ATTRIBUTE = "mojud.businessId";

    private SelectedBusiness() {
    }

    public static Optional<UUID> get(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? Optional.empty() : Optional.ofNullable((UUID) session.getAttribute(SESSION_ATTRIBUTE));
    }

    public static void set(HttpServletRequest request, UUID businessId) {
        request.getSession().setAttribute(SESSION_ATTRIBUTE, businessId);
    }

    static void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }
}
