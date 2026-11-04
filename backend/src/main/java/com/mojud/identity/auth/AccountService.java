package com.mojud.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import com.mojud.shared.error.ErrorCode;
import com.mojud.identity.PasswordConfirmation;
import com.mojud.identity.account.ContactConflicts;
import com.mojud.identity.account.MeResponse;
import com.mojud.identity.account.User;
import com.mojud.identity.account.UserRepository;
import com.mojud.identity.otp.OtpDestination;
import com.mojud.identity.otp.OtpPurpose;
import com.mojud.identity.otp.OtpService;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.phone.PhoneNumber;
import java.time.Clock;
import java.util.UUID;
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
    private final PasswordConfirmation passwordConfirmation;
    private final AuthRateLimits limits;
    private final TransactionTemplate transactions;
    private final Clock clock;

    AccountService(UserRepository users, OtpService otp, PasswordEncoder passwords, LoginSessions sessions,
            PasswordConfirmation passwordConfirmation, AuthRateLimits limits, TransactionTemplate transactions,
            Clock clock) {
        this.users = users;
        this.otp = otp;
        this.passwords = passwords;
        this.sessions = sessions;
        this.passwordConfirmation = passwordConfirmation;
        this.limits = limits;
        this.transactions = transactions;
        this.clock = clock;
    }

    MeResponse me(UUID userId) {
        return users.findById(userId).map(MeResponse::of).orElseThrow(ApiException::notFound);
    }

    /** Null fields stay as they are. A new language applies at once on all the user's devices. */
    MeResponse update(UUID userId, String name, String locale) {
        boolean[] languageChanged = {false};
        MeResponse updated = transactions.execute(status -> {
            User user = users.findById(userId).orElseThrow(ApiException::notFound);
            if (name != null) {
                user.rename(name, clock.instant());
            }
            if (locale != null && !locale.equals(user.getLocale())) {
                user.changeLocale(locale, clock.instant());
                languageChanged[0] = true;
            }
            return MeResponse.of(users.saveAndFlush(user));
        });
        if (languageChanged[0]) {
            sessions.changeLanguage(userId, locale);
        }
        return updated;
    }

    /** Signs the user out everywhere except this device, in case someone else knew the old password. */
    void changePassword(UUID userId, String currentPassword, String newPassword, String currentSessionId,
            String clientIp) {
        passwordConfirmation.confirm(userId, currentPassword, clientIp);
        String hash = passwords.encode(newPassword);
        transactions.executeWithoutResult(status ->
                users.findById(userId).orElseThrow(ApiException::notFound).changePassword(hash, clock.instant()));
        sessions.revokeOthers(userId, currentSessionId);
    }

    /**
     * The first password of an account a business created: replaces the starting one, which had
     * just been typed to sign in, so it isn't asked for again. Other devices are signed out.
     *
     * @throws ApiException {@code access.denied} when the account has its own password already, or
     *         {@code auth.starting_password_expired}
     */
    MeResponse setFirstPassword(UUID userId, String newPassword, HttpServletRequest request) {
        User before = users.findById(userId).orElseThrow(ApiException::notFound);
        if (!before.mustChangePassword()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (before.startingPasswordExpired(clock.instant())) {
            throw new ApiException(ErrorCode.STARTING_PASSWORD_EXPIRED);
        }
        String hash = passwords.encode(newPassword);
        User after = transactions.execute(status -> {
            User user = users.findById(userId).orElseThrow(ApiException::notFound);
            user.changePassword(hash, clock.instant());
            return user;
        });
        PasswordGate.clear(request);
        sessions.revokeOthers(userId, request.getSession().getId());
        return MeResponse.of(after);
    }

    /**
     * Sends a code to prove a phone or email for this account. Nothing is sent when it is already
     * verified here or by another account; the answer is the same, so signed-in users cannot use
     * this to learn which phones and emails have accounts. The code belongs to this user: another
     * user asking for a code to the same address neither replaces nor uses up this one.
     */
    void requestContactCode(UUID userId, OtpDestination destination, String clientIp) {
        otp.requireAvailable(destination.channel());
        limits.codeRequested(destination.value(), OtpPurpose.VERIFY_CONTACT, clientIp);
        User user = users.findById(userId).orElseThrow(ApiException::notFound);
        if (!user.hasVerified(destination.value()) && users.findVerified(destination).isEmpty()) {
            otp.issue(destination, OtpPurpose.VERIFY_CONTACT, userId);
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
        passwordConfirmation.confirm(userId, currentPassword, clientIp);
        String limitKey = PasswordConfirmationService.limitKey(userId);
        try {
            otp.consume(destination, OtpPurpose.VERIFY_CONTACT, userId, code);
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
            throw ContactConflicts.translate(e);
        }
    }
}
