package com.mojud.identity.auth;

import com.mojud.identity.otp.OtpPurpose;
import com.mojud.shared.ratelimit.RateLimiter;
import org.springframework.stereotype.Component;

/**
 * Limits on signing in, signing up and sending codes (backend-plan.md §11; each SMS will cost
 * money). Limits that protect one account or one phone are kept per client IP where possible, so
 * a stranger hammering an account blocks only their own attempts, not the owner. The per-account
 * and per-destination totals sit well above what a person needs and stop attacks spread over many
 * addresses.
 */
@Component
class AuthRateLimits {

    /** Every sign-in attempt from one IP. */
    final RateLimiter loginPerIp;
    /** Failed sign-ins for one account from one IP; cleared by a successful sign-in. */
    final RateLimiter loginFailuresPerAccountAndIp;
    /** Failed sign-ins for one account from anywhere. */
    final RateLimiter loginFailuresPerAccount;
    final RateLimiter signupPerIp;
    final RateLimiter userIdCheckPerIp;
    final RateLimiter otpPerIp;
    /** One code per destination, purpose and IP per minute. */
    final RateLimiter otpCooldown;
    final RateLimiter otpPerDestinationAndIp;
    /** All codes to one destination: caps what one phone number can cost us. */
    final RateLimiter otpPerDestination;

    AuthRateLimits(IdentityProperties properties) {
        IdentityProperties.RateLimits limits = properties.rateLimits();
        this.loginPerIp = limits.loginPerIp().toLimiter("login-per-ip");
        this.loginFailuresPerAccountAndIp = limits.loginFailuresPerAccountAndIp()
                .toLimiter("login-failures-per-account-and-ip");
        this.loginFailuresPerAccount = limits.loginFailuresPerAccount().toLimiter("login-failures-per-account");
        this.signupPerIp = limits.signupPerIp().toLimiter("signup-per-ip");
        this.userIdCheckPerIp = limits.userIdCheckPerIp().toLimiter("user-id-check-per-ip");
        this.otpPerIp = limits.otpPerIp().toLimiter("otp-per-ip");
        this.otpCooldown = limits.otpCooldown().toLimiter("otp-cooldown");
        this.otpPerDestinationAndIp = limits.otpPerDestinationAndIp().toLimiter("otp-per-destination-and-ip");
        this.otpPerDestination = limits.otpPerDestination().toLimiter("otp-per-destination");
    }

    /**
     * Limits on sending a code, applied before anything is looked up. {@code destination} is the
     * phone or email typed, or for a password reset the User ID typed: the limit must apply alike
     * whether or not that ID exists.
     */
    void codeRequested(String destination, OtpPurpose purpose, String clientIp) {
        otpPerIp.acquire(clientIp);
        otpCooldown.acquire(destination + "|" + purpose + "|" + clientIp);
        otpPerDestinationAndIp.acquire(destination + "|" + clientIp);
        otpPerDestination.acquire(destination);
    }

    /**
     * Fails when this account already has too many failed sign-ins, from this IP or overall.
     * {@code account} is the User ID typed, or {@code user:<id>} for a signed-in user re-entering
     * their password.
     */
    void checkLogin(String account, String clientIp) {
        loginPerIp.acquire(clientIp);
        loginFailuresPerAccountAndIp.check(account + "|" + clientIp);
        loginFailuresPerAccount.check(account);
    }

    void loginFailed(String account, String clientIp) {
        loginFailuresPerAccountAndIp.recordFailure(account + "|" + clientIp);
        loginFailuresPerAccount.recordFailure(account);
    }

    /** The overall count is kept: a success here says nothing about attempts from elsewhere. */
    void loginSucceeded(String account, String clientIp) {
        loginFailuresPerAccountAndIp.reset(account + "|" + clientIp);
    }

    /**
     * Password reset is how an owner gets back in while someone attacks the account, so it ignores
     * the per-account total that such an attack fills. Guessing is still bounded: five tries per
     * code, failures per account and IP, and attempts per IP.
     */
    void checkReset(String account, String clientIp) {
        loginPerIp.acquire(clientIp);
        loginFailuresPerAccountAndIp.check(account + "|" + clientIp);
    }

    void resetFailed(String account, String clientIp) {
        loginFailuresPerAccountAndIp.recordFailure(account + "|" + clientIp);
    }

    /**
     * A successful reset proves the owner: earlier failures against the account no longer count,
     * so the owner can sign in with the new password at once.
     */
    void accountRecovered(String userId, String clientIp) {
        loginFailuresPerAccount.reset(userId);
        loginFailuresPerAccountAndIp.reset(userId + "|" + clientIp);
    }
}
