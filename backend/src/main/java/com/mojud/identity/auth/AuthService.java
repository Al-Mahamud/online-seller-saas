package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.identity.account.ContactConflicts;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-up and sign-in. The methods are deliberately not transactional: a code is checked in its
 * own short transaction ({@link OtpService#consume}) before any other work, so a request never
 * holds two database connections, and a failed guess is counted whatever happens afterwards.
 *
 * <p>Only verified phones and emails identify an account (V5__verified_contacts_unique.sql).
 */
@Service
class AuthService {

    private final UserRepository users;
    private final OtpService otp;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authentication;
    private final LoginSessions sessions;
    private final AuthRateLimits limits;
    private final TransactionTemplate transactions;
    private final Clock clock;

    AuthService(UserRepository users, OtpService otp, PasswordEncoder passwords, AuthenticationManager authentication,
            LoginSessions sessions, AuthRateLimits limits, TransactionTemplate transactions, Clock clock) {
        this.users = users;
        this.otp = otp;
        this.passwords = passwords;
        this.authentication = authentication;
        this.sessions = sessions;
        this.limits = limits;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Sends a code only when it can be used: sign-up codes to a phone or email no account has
     * verified, sign-in codes to one an account has verified. The answer is the same either way,
     * the limits apply before the lookup, and sending happens after the answer, so callers cannot
     * learn whether an account exists.
     */
    void requestCode(OtpDestination destination, OtpPurpose purpose, String clientIp) {
        if (purpose == OtpPurpose.VERIFY_CONTACT) {
            // Only for signed-in users, through /api/v1/me/contact/code.
            throw ApiException.invalidField("purpose", "field.invalid_value");
        }
        otp.requireAvailable(destination.channel());
        limits.codeRequested(destination.value(), purpose, clientIp);
        Optional<User> holder = users.findVerified(destination);
        boolean registered = holder.isPresent();
        // A disabled account gets no codes: it cannot use them, and each SMS costs money.
        boolean active = holder.map(User::isActive).orElse(false);
        boolean usable = switch (purpose) {
            case SIGNUP -> !registered;
            case LOGIN, PASSWORD_RESET -> active;
            case VERIFY_CONTACT -> false;
        };
        if (usable) {
            otp.issue(destination, purpose);
        }
    }

    /**
     * Creates the account and signs it in. The code is checked first, so a wrong code never
     * reveals whether the phone or email is taken. Guesses at one code are limited by the code
     * itself (five per code, five codes per hour per destination); the per-IP limit stops a client
     * from trying many codes.
     */
    MeResponse signUp(SignUp command, String clientIp, HttpServletRequest request, HttpServletResponse response) {
        limits.signupPerIp.acquire(clientIp);
        // Checked before the code, so a bad name does not use the code up.
        User.cleanName(command.name());
        OtpDestination verified = command.codeSentToPhone()
                ? OtpDestination.phone(command.phone())
                : OtpDestination.email(command.email());
        otp.consume(verified, OtpPurpose.SIGNUP, command.code());
        // Hashing takes a moment on purpose, so it happens before the transaction starts.
        User account = User.signUp(command.name(), command.phone(), command.email(),
                passwords.encode(command.password()), command.locale(), command.codeSentToPhone(), clock.instant());
        User user;
        try {
            // No "already taken?" query first: the unique indexes decide, which also covers two
            // sign-ups racing for the same phone or email.
            user = transactions.execute(status -> users.saveAndFlush(account));
        } catch (DataIntegrityViolationException e) {
            throw ContactConflicts.translate(e);
        }
        sessions.start(principal(user), user.getLocale(), request, response);
        return MeResponse.of(user);
    }

    MeResponse logInWithPassword(LoginIdentifier login, String password, String clientIp,
            HttpServletRequest request, HttpServletResponse response) {
        limits.checkLogin(login.value(), clientIp);
        Authentication result;
        try {
            result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(login.value(), password));
        } catch (AuthenticationException e) {
            limits.loginFailed(login.value(), clientIp);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        AccountUserDetailsService.Account account = (AccountUserDetailsService.Account) result.getPrincipal();
        return start(users.findById(account.id()).orElseThrow(), login, clientIp, request, response);
    }

    MeResponse logInWithCode(LoginIdentifier login, String code, String clientIp,
            HttpServletRequest request, HttpServletResponse response) {
        limits.checkLogin(login.value(), clientIp);
        try {
            otp.consume(login.destination(), OtpPurpose.LOGIN, code);
        } catch (ApiException e) {
            limits.loginFailed(login.value(), clientIp);
            throw e;
        }
        User user = users.findVerified(login.destination())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        return start(user, login, clientIp, request, response);
    }

    private MeResponse start(User user, LoginIdentifier login, String clientIp,
            HttpServletRequest request, HttpServletResponse response) {
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        limits.loginSucceeded(login.value(), clientIp);
        sessions.start(principal(user), user.getLocale(), request, response);
        return MeResponse.of(user);
    }

    /**
     * Sets a new password after proving the phone or email with a code, and signs the user out on
     * every device, in case someone else knew the old password. Wrong codes count as failed
     * sign-ins. The user then signs in with the new password.
     */
    void resetPassword(LoginIdentifier login, String code, String newPassword, String clientIp) {
        limits.checkReset(login.value(), clientIp);
        try {
            otp.consume(login.destination(), OtpPurpose.PASSWORD_RESET, code);
        } catch (ApiException e) {
            limits.resetFailed(login.value(), clientIp);
            throw e;
        }
        User user = users.findVerified(login.destination())
                .orElseThrow(() -> new ApiException(ErrorCode.OTP_INVALID));
        // Reported only after a right code, as for sign-in, so it reveals nothing to strangers.
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        String hash = passwords.encode(newPassword);
        transactions.executeWithoutResult(status -> {
            User current = users.findById(user.getId()).orElseThrow();
            current.changePassword(hash, clock.instant());
        });
        List<String> logins = new ArrayList<>(List.of(user.getPhone().e164()));
        user.getEmail().ifPresent(email -> logins.add(email.value()));
        limits.accountRecovered(logins, clientIp);
        sessions.revokeAll(user.getId());
    }

    private static AuthenticatedUser principal(User user) {
        return new AuthenticatedUser(user.getId(), user.isPlatformAdmin());
    }

    /** A validated sign-up; {@code email} may be null only when the code went to the phone. */
    record SignUp(String name, PhoneNumber phone, EmailAddress email, String password, String locale,
            boolean codeSentToPhone, String code) {
    }
}
