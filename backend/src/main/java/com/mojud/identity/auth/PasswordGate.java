package com.mojud.identity.auth;

import com.mojud.identity.account.User;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Someone signed in with a starting password a business gave them may do nothing but set their
 * own (and read who they are, or sign out) until they have. The session remembers it from sign-in;
 * a new starting password signs every device out, so it is never out of date.
 */
@Component
class PasswordGate implements HandlerInterceptor {

    static final String MUST_CHANGE = "mojud.mustChangePassword";

    /** What a gated session may still reach, as "METHOD path". */
    private static final Set<String> OPEN = Set.of(
            "GET /api/v1/me",
            // Which business added them, and as what, for the page that asks for the password.
            "GET /api/v1/business",
            "POST /api/v1/me/first-password",
            "POST /api/v1/auth/logout");

    static void mark(HttpServletRequest request, User user) {
        if (user.mustChangePassword()) {
            request.getSession().setAttribute(MUST_CHANGE, Boolean.TRUE);
        }
    }

    static void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(MUST_CHANGE);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(MUST_CHANGE) == null) {
            return true;
        }
        String call = request.getMethod() + " " + request.getRequestURI();
        // Signing in (perhaps as someone else) and the like stay open.
        if (OPEN.contains(call) || request.getRequestURI().startsWith("/api/v1/auth/")
                || !request.getRequestURI().startsWith("/api/")) {
            return true;
        }
        throw new ApiException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
    }
}
