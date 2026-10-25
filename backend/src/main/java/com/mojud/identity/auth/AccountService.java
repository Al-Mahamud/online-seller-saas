package com.mojud.identity.auth;

import com.mojud.identity.account.MeResponse;
import com.mojud.identity.account.User;
import com.mojud.identity.account.UserRepository;
import com.mojud.identity.otp.OtpDestination;
import com.mojud.identity.otp.OtpPurpose;
import com.mojud.identity.otp.OtpService;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.phone.PhoneNumber;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signed-in user's own account (implementation-plan.md 1b). Like {@link AuthService}, the
 * methods are not transactional: codes are checked and sessions changed outside the short
 * transaction that writes the user, so a request holds one database connection at a time.
 *
 * <p>Re-entering the password counts against the sign-in failure limits under
 * {@code user:<id>}, so a stolen session cannot be used to guess it.
 */
@Service
class AccountService {

    private final UserRepository users;
    private final OtpService otp;
    private final PasswordEncoder passwords;
    private final LoginSessions sessions;
    private final AuthRateLimits limits;
    private final TransactionTemplate transactions;
    private final Clock clock;

    AccountService(UserRepository users, OtpService otp, PasswordEncoder passwords, LoginSessions sessions,
            AuthRateLimits limits, TransactionTemplate transactions, Clock clock) {
        this.users = users;
        this.otp = otp;
        this.passwords = passwords;
        this.sessions = sessions;
        this.limits = limits;
        this.transactions = transactions;
        this.clock = clock;
    }

    MeResponse me(UUID userId) {
        return users.findById(userId).map(MeResponse::of).orElseThrow(ApiException::notFound);
    }

    /** Null fields stay as they are. A new language applies at once on all the user's devices. */
    MeResponse update(UUID userId, String name, String locale) {
        MeResponse updated = transactions.execute(status -> {
            User user = users.findById(userId).orElseThrow(ApiException::notFound);
            if (name != null) {
                user.rename(name, clock.instant());
            }
            if (locale != null) {
                user.changeLocale(locale, clock.instant());
            }
            return MeResponse.of(users.saveAndFlush(user));
        });
        if (locale != null) {
            sessions.changeLanguage(userId, locale);
        }
        return updated;
    }

    /** Signs the user out everywhere except this device, in case someone else knew the old password. */
    void changePassword(UUID userId, String currentPassword, String newPassword, String currentSessionId,
            String clientIp) {
        requirePassword(userId, currentPassword, clientIp);
        String hash = passwords.encode(newPassword);
        transactions.executeWithoutResult(status ->
                users.findById(userId).orElseThrow(ApiException::notFound).changePassword(hash, clock.instant()));
        sessions.revokeOthers(userId, currentSessionId);
    }

    /**
     * Sends a code to prove a phone or email for this account. Nothing is sent when it is already
     * verified here or by another account; the answer is the same, so signed-in users cannot use
     * this to learn which phones and emails have accounts.
     */
    void requestContactCode(UUID userId, OtpDestination destination, String clientIp) {
        otp.requireAvailable(destination.channel());
        limits.codeRequested(destination.value(), OtpPurpose.VERIFY_CONTACT, clientIp);
        User user = users.findById(userId).orElseThrow(ApiException::notFound);
        if (!user.hasVerified(destination.value()) && findVerified(destination).isEmpty()) {
            otp.issue(destination, OtpPurpose.VERIFY_CONTACT);
        }
    }

    /**
     * Makes the phone or email that received the code this account's verified one, replacing the
     * previous phone or email: it is how people add an email, prove the phone they signed up with,
     * or move to a new number. Needs the current password too, so a stolen session cannot move
     * the account to an attacker's email and then reset the password.
     */
    MeResponse confirmContact(UUID userId, OtpDestination destination, String code, String currentPassword,
            String clientIp) {
        requirePassword(userId, currentPassword, clientIp);
        String limitKey = "user:" + userId;
        try {
            otp.consume(destination, OtpPurpose.VERIFY_CONTACT, code);
        } catch (ApiException e) {
            limits.loginFailed(limitKey, clientIp);
            throw e;
        }
        try {
            return transactions.execute(status -> {
                User user = users.findById(userId).orElseThrow(ApiException::notFound);
                if (destination.isPhone()) {
                    user.verifyPhone(new PhoneNumber(destination.value()), clock.instant());
                } else {
                    user.verifyEmail(new EmailAddress(destination.value()), clock.instant());
                }
                // The unique indexes decide if another account proved it meanwhile.
                return MeResponse.of(users.saveAndFlush(user));
            });
        } catch (DataIntegrityViolationException e) {
            throw contactTaken(e);
        }
    }

    private void requirePassword(UUID userId, String password, String clientIp) {
        String limitKey = "user:" + userId;
        limits.checkLogin(limitKey, clientIp);
        String hash = users.findById(userId).map(User::getPasswordHash).orElseThrow(ApiException::notFound);
        if (!passwords.matches(password, hash)) {
            limits.loginFailed(limitKey, clientIp);
            throw new ApiException(ErrorCode.WRONG_PASSWORD);
        }
        limits.loginSucceeded(limitKey, clientIp);
    }

    private Optional<User> findVerified(OtpDestination destination) {
        return destination.isPhone()
                ? users.findByVerifiedPhone(destination.value())
                : users.findByVerifiedEmail(destination.value());
    }

    static RuntimeException contactTaken(DataIntegrityViolationException e) {
        String reason = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
        if (reason.contains("uq_users_verified_phone")) {
            return new ApiException(ErrorCode.PHONE_TAKEN);
        }
        if (reason.contains("uq_users_verified_email")) {
            return new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        return e;
    }
}
