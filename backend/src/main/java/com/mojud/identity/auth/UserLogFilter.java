package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the signed-in user's id in the log context (MDC {@code userId}), so each line written while
 * the request runs shows who it was for: {@code U[…]}. Runs after Spring Security has loaded the
 * session. {@code RequestIdFilter} clears it when the request ends, after the answer's log line.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
class UserLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            MDC.put(RequestIdFilter.USER_MDC_KEY, user.id().toString());
        }
        chain.doFilter(request, response);
    }
}
