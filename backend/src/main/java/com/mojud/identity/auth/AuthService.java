package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
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
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {

    private final UserRepository users;
    private final OtpService otp;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authentication;
    private final LoginSessions sessions;
    private final AuthRateLimits limits;
    private final Clock clock;

    AuthService(UserRepository users, OtpService otp, PasswordEncoder passwords,
            AuthenticationManager authentication, LoginSessions sessions, AuthRateLimits limits, Clock clock) {
        this.users = users;
        this.otp = otp;
        this.passwords = passwords;
        this.authentication = authentication;
        this.sessions = sessions;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Sends a code only when it can be used: sign-up codes to a phone or email without an account,
     * sign-in codes to one with an account. The answer is the same either way, and the limits apply
     * before the lookup, so callers cannot learn whether an account exists.
     */
    void requestCode(OtpDestination destination, OtpPurpose purpose, String clientIp) {
        otp.requireAvailable(destination.channel());
        limits.otpPerIp.acquire(clientIp);
        limits.otpCooldown.acquire(destination.value() + ":" + purpose);
        limits.otpPerDestination.acquire(destination.value());
        boolean registered = isRegistered(destination);
        boolean usable = switch (purpose) {
            case SIGNUP -> !registered;
            case LOGIN -> registered;
        };
        if (usable) {
            otp.issue(destination, purpose);
        }
    }

    /**
     * Creates the account and signs it in. The code is checked first, so a wrong code never
     * reveals whether the phone or email is taken.
     */
    @Transactional
    MeResponse signUp(SignUp command, HttpServletRequest request, HttpServletResponse response) {
        OtpDestination verified = command.codeSentToPhone()
                ? OtpDestination.phone(command.phone())
                : OtpDestination.email(command.email());
        otp.consume(verified, OtpPurpose.SIGNUP, command.code());
        if (users.existsByPhone(command.phone().e164())) {
            throw new ApiException(ErrorCode.PHONE_TAKEN);
        }
        if (command.email() != null && users.existsByEmail(command.email().value())) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        User user = users.saveAndFlush(User.signUp(command.name(), command.phone(), command.email(),
                passwords.encode(command.password()), command.locale(), command.codeSentToPhone(), clock.instant()));
        sessions.start(principal(user), request, response);
        return MeResponse.of(user);
    }

    @Transactional(readOnly = true)
    MeResponse logInWithPassword(LoginIdentifier login, String password, String clientIp,
            HttpServletRequest request, HttpServletResponse response) {
        limits.loginPerIp.acquire(clientIp);
        limits.loginPerAccount.acquire(login.value());
        Authentication result;
        try {
            result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(login.value(), password));
        } catch (AuthenticationException e) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        AccountUserDetailsService.Account account = (AccountUserDetailsService.Account) result.getPrincipal();
        return start(users.findById(account.id()).orElseThrow(), request, response);
    }

    /** Receiving the code also proves the phone or email belongs to the user. */
    @Transactional
    MeResponse logInWithCode(LoginIdentifier login, String code, String clientIp,
            HttpServletRequest request, HttpServletResponse response) {
        limits.loginPerIp.acquire(clientIp);
        limits.loginPerAccount.acquire(login.value());
        otp.consume(login.destination(), OtpPurpose.LOGIN, code);
        User user = find(login.destination()).orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        user.markVerified(login.destination().isPhone(), clock.instant());
        return start(user, request, response);
    }

    private MeResponse start(User user, HttpServletRequest request, HttpServletResponse response) {
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        sessions.start(principal(user), request, response);
        return MeResponse.of(user);
    }

    private boolean isRegistered(OtpDestination destination) {
        return destination.isPhone()
                ? users.existsByPhone(destination.value())
                : users.existsByEmail(destination.value());
    }

    private Optional<User> find(OtpDestination destination) {
        return destination.isPhone()
                ? users.findByPhone(destination.value())
                : users.findByEmail(destination.value());
    }

    private static AuthenticatedUser principal(User user) {
        return new AuthenticatedUser(user.getId(), user.isPlatformAdmin());
    }

    /** A validated sign-up; {@code email} may be null only when the code went to the phone. */
    record SignUp(String name, PhoneNumber phone, EmailAddress email, String password, String locale,
            boolean codeSentToPhone, String code) {
    }
}
