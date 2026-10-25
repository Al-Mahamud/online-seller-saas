package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Starts server-side sessions (stored in PostgreSQL by Spring Session). Phase 1b adds revoking them. */
@Component
class LoginSessions {

    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    void start(AuthenticatedUser user, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            // A new session id at sign-in prevents session fixation.
            request.changeSessionId();
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication(user));
        holder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    private static Authentication authentication(AuthenticatedUser user) {
        List<GrantedAuthority> authorities = user.platformAdmin()
                ? List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                : List.of();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }
}
