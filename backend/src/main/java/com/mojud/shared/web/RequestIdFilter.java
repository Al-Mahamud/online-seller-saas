package com.mojud.shared.web;

import com.mojud.shared.time.DhakaTime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID, so what someone saw can be matched with the server's log lines for
 * it. The ID is in every log line written while the request runs (MDC {@code requestId}, shown as
 * {@code T[…]}, docs/logging.md; a Sentry tag), in the {@code X-Request-Id}
 * response header, and in every error body as {@code requestId}, which the app shows on its error
 * screen. A well-formed ID from the caller, such as a proxy's, is kept; anything else is replaced.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    /** The signed-in user's id, set once Spring Security has loaded the session; cleared here. */
    public static final String USER_MDC_KEY = "userId";

    private static final String ATTRIBUTE = RequestIdFilter.class.getName();
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(DhakaTime.ZONE);
    // Letters, digits, '-' and '_' only, so a caller can't break or forge log lines with it.
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /** The ID of the request this thread is serving, if any. */
    public static @Nullable String current() {
        return MDC.get(MDC_KEY);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // An error or async dispatch of the same request keeps the ID it was given first.
        String id = request.getAttribute(ATTRIBUTE) instanceof String known ? known : given(request);
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(USER_MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private static String given(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        return header != null && WELL_FORMED.matcher(header).matches() ? header : newId();
    }

    /**
     * The time in Dhaka to the second, then 7 hex digits: 261110140020-e38a8e5. The time says which
     * hourly log file holds the request; the digits keep two requests in one second apart.
     */
    static String newId() {
        return newId(Instant.now());
    }

    static String newId(Instant at) {
        byte[] bytes = new byte[4];
        ThreadLocalRandom.current().nextBytes(bytes);
        return ID_TIME.format(at) + "-" + HexFormat.of().formatHex(bytes).substring(1);
    }
}
