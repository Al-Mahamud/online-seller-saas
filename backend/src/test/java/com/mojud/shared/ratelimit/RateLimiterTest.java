package com.mojud.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import com.mojud.shared.error.ApiException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final RateLimiter limiter = new RateLimiter("test", 3, Duration.ofMinutes(15), ticker);

    @Test
    void allowsUpToTheLimitPerKey() {
        limiter.acquire("a");
        limiter.acquire("a");
        limiter.acquire("a");
        limiter.acquire("b");

        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(ApiException.class);
    }

    @Test
    void reportsTheTimeLeftInTheWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("a");
        }
        advance(Duration.ofMinutes(10));

        assertThatThrownBy(() -> limiter.acquire("a"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details())
                        .containsEntry(ApiException.RETRY_AFTER_SECONDS, 300L));
    }

    @Test
    void theWindowStartsAtTheFirstAttemptAndDoesNotSlide() {
        limiter.acquire("a");
        advance(Duration.ofMinutes(14));
        limiter.acquire("a");
        limiter.acquire("a");
        advance(Duration.ofMinutes(2));

        limiter.acquire("a");
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
