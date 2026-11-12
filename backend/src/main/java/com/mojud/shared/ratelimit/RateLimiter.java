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
 * Allows at most {@code limit} counted events per key in a fixed window that starts at the key's
 * first event. Counters live in memory, so they reset on restart and are per instance; that is
 * enough while one instance serves the API (backend-plan.md §2).
 *
 * <p>Two ways to use it: {@link #acquire} counts every attempt; {@link #check} plus
 * {@link #recordFailure} and {@link #reset} count only failures, so a user who gets it right is
 * never locked out by their own earlier typos.
 *
 * <p>There is no size cap: evicting a live counter would reset a limit an attacker is working
 * against. Memory stays bounded because counters expire with their window and every key-creating
 * call is first limited per client IP.
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
        // The entry is written once, on the first event, so the window does not slide.
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .ticker(ticker)
                .build();
    }

    /** Counts one attempt for {@code key}, or fails with {@code rate_limited} once over the limit. */
    public void acquire(String key) {
        Window current = window(key);
        int count = current.events.incrementAndGet();
        if (count == limit + 1) {
            // Unusual activity shows in the logs, once per key and window, not per attempt.
            log.warn("[RATE-LIMIT] {} reached for {}", name, key);
        }
        if (count > limit) {
            throw limited(current);
        }
    }

    /** Fails with {@code rate_limited} when {@code key} already has {@code limit} recorded failures. */
    public void check(String key) {
        Window current = windows.getIfPresent(key);
        if (current != null && current.events.get() >= limit) {
            throw limited(current);
        }
    }

    public void recordFailure(String key) {
        if (window(key).events.incrementAndGet() == limit) {
            log.warn("[RATE-LIMIT] {} reached for {}", name, key);
        }
    }

    /** Forgets the failures of {@code key}, after it succeeded. */
    public void reset(String key) {
        windows.invalidate(key);
    }

    private Window window(String key) {
        return windows.get(key, k -> new Window(ticker.read()));
    }

    /** The answer carries the time until the window ends, sent to the client as {@code Retry-After}. */
    private ApiException limited(Window current) {
        return ApiException.rateLimited(window.minus(Duration.ofNanos(ticker.read() - current.startedAt)));
    }

    private static final class Window {

        final long startedAt;
        final AtomicInteger events = new AtomicInteger();

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
