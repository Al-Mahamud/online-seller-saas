package com.mojud.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.mojud.shared.error.ApiException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows at most {@code limit} attempts per key in a fixed window that starts at the key's first
 * attempt. Counters live in memory, so they reset on restart and are per instance; that is enough
 * while one instance serves the API (backend-plan.md §2).
 */
public final class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final String name;
    private final int limit;
    private final Duration window;
    private final Ticker ticker;
    private final Cache<String, Window> windows;

    /** @param name what is limited, e.g. "login-per-ip"; it appears in the log when a limit is hit */
    public RateLimiter(String name, int limit, Duration window) {
        this(name, limit, window, Ticker.systemTicker());
    }

    RateLimiter(String name, int limit, Duration window, Ticker ticker) {
        this.name = name;
        this.limit = limit;
        this.window = window;
        this.ticker = ticker;
        // The entry is written once, on the first attempt, so the window does not slide.
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(100_000)
                .ticker(ticker)
                .build();
    }

    /**
     * Counts one attempt for {@code key}, or fails with {@code rate_limited} and the time until the
     * window ends (sent to the client as {@code Retry-After}).
     */
    public void acquire(String key) {
        Window current = windows.get(key, k -> new Window(ticker.read()));
        int count = current.attempts.incrementAndGet();
        if (count == limit + 1) {
            // Unusual activity shows in the logs, once per key and window, not per attempt.
            log.warn("Rate limit {} reached for {}", name, key);
        }
        if (count > limit) {
            Duration elapsed = Duration.ofNanos(ticker.read() - current.startedAt);
            throw ApiException.rateLimited(window.minus(elapsed));
        }
    }

    private static final class Window {

        final long startedAt;
        final AtomicInteger attempts = new AtomicInteger();

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
