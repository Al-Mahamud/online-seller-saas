package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.web.LocaleConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    static final String PENDING_SECOND_STEP = "mojud.pendingSecondStep";
    static final Duration SECOND_STEP_TIME = Duration.ofMinutes(5);
    static final int SECOND_STEP_TRIES = 5;

    /** Signed in up to the second step: who, as typed (for the failure limits), until when. */
    record PendingSecondStep(UUID userId, String login, Instant expiresAt, int failures) implements Serializable {
    }

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
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            // A brand-new session at sign-in: a new id prevents session fixation, and nothing from
            // the previous user of this browser (such as their selected business) carries over.
            previous.invalidate();
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication(user));
        holder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession().setAttribute(SessionCookieRenewal.RENEWED_AT, clock.instant());
        request.getSession().setAttribute(LocaleConfig.SESSION_LANGUAGE, language);
    }

    /**
     * The password (or sign-in code) was right and the account has two-step sign-in: a new session,
     * not signed in, remembers who is half-way for a few minutes ({@link PendingSecondStep}).
     */
    void awaitSecondStep(UUID userId, String login, HttpServletRequest request) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        request.getSession().setAttribute(PENDING_SECOND_STEP,
                new PendingSecondStep(userId, login, clock.instant().plus(SECOND_STEP_TIME), 0));
    }

    /** Who is half-way through signing in on this device, while there is time left. */
    Optional<PendingSecondStep> pendingSecondStep(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object pending = session == null ? null : session.getAttribute(PENDING_SECOND_STEP);
        return pending instanceof PendingSecondStep step && clock.instant().isBefore(step.expiresAt())
                ? Optional.of(step)
                : Optional.empty();
    }

    /** A wrong second-step code: after {@value #SECOND_STEP_TRIES}, the password is needed again. */
    void secondStepFailed(PendingSecondStep step, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        if (step.failures() + 1 >= SECOND_STEP_TRIES) {
            session.removeAttribute(PENDING_SECOND_STEP);
        } else {
            session.setAttribute(PENDING_SECOND_STEP,
                    new PendingSecondStep(step.userId(), step.login(), step.expiresAt(), step.failures() + 1));
        }
    }

    /**
     * Replaces who is signed in on this device (two-step sign-in turned on or off), under a new
     * session id, as at sign-in.
     */
    void replace(AuthenticatedUser user, HttpServletRequest request, HttpServletResponse response) {
        request.changeSessionId();
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication(user));
        holder.setContext(context);
        contexts.saveContext(context, request, response);
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

    /** Admin rights only with two-step sign-in; SecurityConfig tells an admin without it what to do. */
    private static Authentication authentication(AuthenticatedUser user) {
        List<GrantedAuthority> authorities = user.platformAdmin() && user.twoFactor()
                ? List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                : List.of();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }
}
