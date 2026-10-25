package com.mojud.identity.auth;

import com.mojud.shared.ratelimit.RateLimiter;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code mojud.identity.*} in application.yml. */
@ConfigurationProperties("mojud.identity")
public record IdentityProperties(Otp otp, RateLimits rateLimits) {

    public record Otp(Duration ttl, int maxAttempts) {
    }

    public record RateLimits(
            Limit loginPerIp,
            Limit loginFailuresPerAccountAndIp,
            Limit loginFailuresPerAccount,
            Limit signupPerIp,
            Limit otpPerIp,
            Limit otpCooldown,
            Limit otpPerDestinationAndIp,
            Limit otpPerDestination) {
    }

    public record Limit(int max, Duration window) {

        RateLimiter toLimiter(String name) {
            return new RateLimiter(name, max, window);
        }
    }
}
