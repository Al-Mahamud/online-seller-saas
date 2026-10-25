package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.web.LocaleConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Starts and ends server-side sessions (stored in PostgreSQL by Spring Session). Sessions are
 * indexed by user id ({@link AuthenticatedUser#getName()}), so all of a user's devices can be found.
 *
 * <p>Call these outside database transactions: Spring Session uses its own connection.
 */
@Component
class LoginSessions {

    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Clock clock;

    LoginSessions(FindByIndexNameSessionRepository<? extends Session> sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Signs the user in on this device, in their saved language. */
    void start(AuthenticatedUser user, String language, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            // A new session id at sign-in prevents session fixation.
            request.changeSessionId();
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication(user));
        holder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession().setAttribute(SessionCookieRenewal.RENEWED_AT, clock.instant());
        request.getSession().setAttribute(LocaleConfig.SESSION_LANGUAGE, language);
    }

    /** Switches every device of this user to {@code language}. */
    void changeLanguage(UUID userId, String language) {
        changeLanguage(sessions, userId, language);
    }

    private static <S extends Session> void changeLanguage(FindByIndexNameSessionRepository<S> repository,
            UUID userId, String language) {
        for (S session : repository.findByPrincipalName(userId.toString()).values()) {
            session.setAttribute(LocaleConfig.SESSION_LANGUAGE, language);
            repository.save(session);
        }
    }

    /** Signs the user out on every device. */
    void revokeAll(UUID userId) {
        sessions.findByPrincipalName(userId.toString()).keySet().forEach(sessions::deleteById);
    }

    /** Signs the user out on every device except the one with {@code currentSessionId}. */
    void revokeOthers(UUID userId, String currentSessionId) {
        sessions.findByPrincipalName(userId.toString()).keySet().stream()
                .filter(sessionId -> !sessionId.equals(currentSessionId))
                .forEach(sessions::deleteById);
    }

    private static Authentication authentication(AuthenticatedUser user) {
        List<GrantedAuthority> authorities = user.platformAdmin()
                ? List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                : List.of();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }
}
