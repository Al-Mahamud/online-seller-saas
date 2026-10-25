package com.mojud.identity.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the session cookie alive while the user is active. Spring Session writes the cookie only
 * when the session starts, so with a 7-day max-age a seller who uses the app every day would still
 * be signed out 7 days after signing in. This filter sends the cookie again, at most once a day,
 * so 7 days means 7 days without using the app, matching the server-side idle timeout.
 */
@Component
class SessionCookieRenewal extends OncePerRequestFilter {

    /** When the cookie was last sent; set at sign-in by {@link LoginSessions}. */
    static final String RENEWED_AT = "mojud.sessionCookieRenewedAt";

    private static final Duration INTERVAL = Duration.ofDays(1);

    private final CookieSerializer cookies;
    private final Clock clock;

    SessionCookieRenewal(CookieSerializer cookies, Clock clock) {
        this.cookies = cookies;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null) {
            Instant now = clock.instant();
            Instant renewedAt = (Instant) session.getAttribute(RENEWED_AT);
            if (renewedAt == null || !now.isBefore(renewedAt.plus(INTERVAL))) {
                cookies.writeCookieValue(new CookieSerializer.CookieValue(request, response, session.getId()));
                session.setAttribute(RENEWED_AT, now);
            }
        }
        chain.doFilter(request, response);
    }
}
