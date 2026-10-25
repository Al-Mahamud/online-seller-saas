package com.mojud.identity.auth;

import com.mojud.shared.ratelimit.RateLimiter;
import org.springframework.stereotype.Component;

/** Limits on signing in and on sending codes (backend-plan.md §11; each SMS will cost money). */
@Component
class AuthRateLimits {

    final RateLimiter loginPerAccount;
    final RateLimiter loginPerIp;
    final RateLimiter otpCooldown;
    final RateLimiter otpPerDestination;
    final RateLimiter otpPerIp;

    AuthRateLimits(IdentityProperties properties) {
        IdentityProperties.RateLimits limits = properties.rateLimits();
        this.loginPerAccount = limits.loginPerAccount().toLimiter("login-per-account");
        this.loginPerIp = limits.loginPerIp().toLimiter("login-per-ip");
        this.otpCooldown = limits.otpCooldown().toLimiter("otp-cooldown");
        this.otpPerDestination = limits.otpPerDestination().toLimiter("otp-per-destination");
        this.otpPerIp = limits.otpPerIp().toLimiter("otp-per-ip");
    }
}
