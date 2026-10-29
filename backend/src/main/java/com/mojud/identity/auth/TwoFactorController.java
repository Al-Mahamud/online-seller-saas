package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.identity.PasswordConfirmation;
import com.mojud.identity.account.User;
import com.mojud.identity.account.UserRepository;
import com.mojud.identity.twofactor.TwoFactor;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's two-step sign-in (TwoFactor). Turning it on or off needs the password and a
 * code; wrong codes count against the sign-in failure limits, as wrong passwords do. Turning it on
 * signs out the user's other devices (they did not pass it) and marks this one as having passed.
 */
@RestController
@RequestMapping("/api/v1/me/two-factor")
class TwoFactorController {

    private final TwoFactor twoFactor;
    private final PasswordConfirmation passwords;
    private final UserRepository users;
    private final LoginSessions sessions;
    private final AuthRateLimits limits;
    private final SecurityEmails emails;

    TwoFactorController(TwoFactor twoFactor, PasswordConfirmation passwords, UserRepository users, LoginSessions sessions,
            AuthRateLimits limits, SecurityEmails emails) {
        this.twoFactor = twoFactor;
        this.passwords = passwords;
        this.users = users;
        this.sessions = sessions;
        this.limits = limits;
        this.emails = emails;
    }

    @GetMapping
    TwoFactor.Status status(@AuthenticationPrincipal AuthenticatedUser me) {
        return twoFactor.status(me.id());
    }

    record PasswordBody(@NotBlank @Size(max = 128) String password) {
    }

    /** A secret to scan; replaces an unfinished setup. Turn it on with a code: {@code POST /enable}. */
    @PostMapping("/setup")
    TwoFactor.Setup setup(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody PasswordBody body,
            HttpServletRequest request) {
        passwords.confirm(me.id(), body.password(), request.getRemoteAddr());
        User user = users.findById(me.id()).orElseThrow();
        return twoFactor.setup(me.id(), user.getPhone().e164());
    }

    record CodeBody(@NotBlank @Size(max = 20) String code) {
    }

    record RecoveryCodes(List<String> recoveryCodes) {
    }

    /** Turns it on with a code from the app. The recovery codes are shown this once. */
    @PostMapping("/enable")
    RecoveryCodes enable(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody CodeBody body,
            HttpServletRequest request, HttpServletResponse response) {
        List<String> codes = checked(me, request, () -> twoFactor.enable(me.id(), body.code()));
        // Others first: the session store knows this session by its id until the request ends.
        sessions.revokeOthers(me.id(), request.getSession().getId());
        sessions.replace(new AuthenticatedUser(me.id(), me.platformAdmin(), true), request, response);
        emails.twoFactorOn(users.findById(me.id()).orElseThrow());
        return new RecoveryCodes(codes);
    }

    /** New recovery codes, with a code from the app; the old ones stop working. */
    @PostMapping("/recovery-codes")
    RecoveryCodes recoveryCodes(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody CodeBody body,
            HttpServletRequest request) {
        return new RecoveryCodes(checked(me, request, () -> twoFactor.replaceRecoveryCodes(me.id(), body.code())));
    }

    record DisableBody(@NotBlank @Size(max = 128) String password, @NotBlank @Size(max = 20) String code) {
    }

    /** Turns it off with the password and a code (from the app, or a recovery code). */
    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody DisableBody body,
            HttpServletRequest request, HttpServletResponse response) {
        passwords.confirm(me.id(), body.password(), request.getRemoteAddr());
        checked(me, request, () -> twoFactor.verify(me.id(), body.code()));
        twoFactor.disable(me.id());
        sessions.replace(new AuthenticatedUser(me.id(), me.platformAdmin(), false), request, response);
        emails.twoFactorOff(users.findById(me.id()).orElseThrow());
    }

    /** Runs a code check under the failure limits for this user (as for re-entered passwords). */
    private <T> T checked(AuthenticatedUser me, HttpServletRequest request, Supplier<T> check) {
        String account = "user:" + me.id();
        limits.checkLogin(account, request.getRemoteAddr());
        try {
            return check.get();
        } catch (ApiException e) {
            if (e.code() == ErrorCode.TWO_FACTOR_INVALID_CODE) {
                limits.loginFailed(account, request.getRemoteAddr());
            }
            throw e;
        }
    }
}
