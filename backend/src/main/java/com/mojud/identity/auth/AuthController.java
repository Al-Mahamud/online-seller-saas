package com.mojud.identity.auth;

import com.mojud.identity.account.MeResponse;
import com.mojud.identity.account.UserIds;
import com.mojud.identity.otp.OtpChannel;
import com.mojud.identity.otp.OtpPurpose;
import com.mojud.identity.otp.OtpService;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.phone.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-up and sign-in. Sign-out is Spring Security's {@code POST /api/v1/auth/logout} (see
 * SecurityConfig). Phone numbers are accepted in any common form and stored as E.164.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private static final String SIX_DIGITS = "\\d{6}";

    /** Longer than any real ID, as typed with spaces; keeps junk out of the rate limiters. */
    private static final int MAX_USER_ID_INPUT = 64;

    private final AuthService auth;
    private final OtpService otp;

    AuthController(AuthService auth, OtpService otp) {
        this.auth = auth;
        this.otp = otp;
    }

    record Options(Set<OtpChannel> otpChannels) {
    }

    /** Which ways of receiving a code work right now (no SMS until a gateway is set up). */
    @GetMapping("/options")
    Options options() {
        return new Options(otp.availableChannels());
    }

    /** Call once before the first state-changing request: it sets the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void csrf(CsrfToken token) {
        token.getToken();
    }

    /**
     * {@code to} is a phone number or an email address; {@code purpose} is SIGNUP or
     * USER_ID_RECOVERY. A password reset's code is asked for at {@code /password/code}.
     */
    record CodeRequest(@NotBlank String to, @NotNull OtpPurpose purpose) {
    }

    /** Always 202, whether or not a code was sent (see AuthService#requestCode). */
    @PostMapping("/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestCode(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        ContactInput destination = ContactInput.parse(body.to(), "to");
        auth.requestCode(destination.destination(), body.purpose(), request.getRemoteAddr());
    }

    record UserIdAvailability(boolean available) {
    }

    /**
     * Whether a User ID can be chosen, for the check button on the sign-up page. Sign-up decides
     * for certain: someone else may take the ID in between.
     */
    @GetMapping("/user-id/availability")
    UserIdAvailability userIdAvailability(@RequestParam String userId, HttpServletRequest request) {
        return new UserIdAvailability(auth.isUserIdAvailable(userId, request.getRemoteAddr()));
    }

    record RecoveredUserId(String userId) {
    }

    record UserIdRecoveryRequest(
            @NotBlank String to,
            @NotBlank @Pattern(regexp = SIX_DIGITS, message = "{field.six_digit_code}") String code) {
    }

    /** The User ID of the account that verified this phone or email, with a USER_ID_RECOVERY code sent to it. */
    @PostMapping("/user-id/recover")
    RecoveredUserId recoverUserId(@Valid @RequestBody UserIdRecoveryRequest body, HttpServletRequest request) {
        return new RecoveredUserId(auth.recoverUserId(ContactInput.parse(body.to(), "to").destination(), body.code(),
                request.getRemoteAddr()));
    }

    record SignUpRequest(
            @NotBlank @Size(max = MAX_USER_ID_INPUT) String userId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank String phone,
            String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            /** Where the sign-up code was sent: PHONE or EMAIL. */
            @NotNull OtpChannel codeSentTo,
            @NotBlank @Pattern(regexp = SIX_DIGITS, message = "{field.six_digit_code}") String code) {
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    MeResponse signUp(@Valid @RequestBody SignUpRequest body, HttpServletRequest request, HttpServletResponse response) {
        PhoneNumber phone = PhoneNumber.fromInput(body.phone(), "phone");
        EmailAddress email = null;
        if (body.email() != null && !body.email().isBlank()) {
            email = EmailAddress.tryParse(body.email())
                    .orElseThrow(() -> ApiException.invalidField("email", "field.email_invalid"));
        } else if (body.codeSentTo() == OtpChannel.EMAIL) {
            throw ApiException.invalidField("email", "jakarta.validation.constraints.NotBlank.message");
        }
        String locale = "bn".equals(LocaleContextHolder.getLocale().getLanguage()) ? "bn" : "en";
        return auth.signUp(new AuthService.SignUp(UserIds.require(body.userId(), "userId"), body.name(), phone, email,
                body.password(), locale, body.codeSentTo() == OtpChannel.PHONE, body.code()),
                request.getRemoteAddr(), request, response);
    }

    record PasswordCodeRequest(@NotBlank @Size(max = MAX_USER_ID_INPUT) String userId) {
    }

    /** Always 202, whether or not a code was sent (see AuthService#requestPasswordCode). */
    @PostMapping("/password/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestPasswordCode(@Valid @RequestBody PasswordCodeRequest body, HttpServletRequest request) {
        auth.requestPasswordCode(UserIds.normalize(body.userId()), request.getRemoteAddr());
    }

    record PasswordResetRequest(
            @NotBlank @Size(max = MAX_USER_ID_INPUT) String userId,
            @NotBlank @Pattern(regexp = SIX_DIGITS, message = "{field.six_digit_code}") String code,
            @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    /**
     * Sets a new password with a PASSWORD_RESET code sent to the account's verified phone or
     * email, and signs out every device.
     */
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody PasswordResetRequest body, HttpServletRequest request) {
        auth.resetPassword(UserIds.normalize(body.userId()), body.code(), body.newPassword(),
                request.getRemoteAddr());
    }

    /** Bad input that can't be an ID gets the same answer as a wrong password, not a rule to read. */
    record LoginRequest(
            @NotBlank @Size(max = MAX_USER_ID_INPUT) String userId,
            @NotBlank @Size(max = 128) String password) {
    }

    /** Asked of an account with two-step sign-in: send a code from the app to {@code /login/two-factor}. */
    record SecondStep(boolean twoFactorRequired) {
    }

    /**
     * 200 with the account when signed in; 202 {@code {"twoFactorRequired": true}} when the account
     * has two-step sign-in, to be finished within five minutes.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MeResponse.class)))
    @ApiResponse(responseCode = "202", content = @Content(schema = @Schema(implementation = SecondStep.class)))
    @PostMapping("/login")
    ResponseEntity<Object> logIn(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Optional<MeResponse> me = auth.logIn(UserIds.normalize(body.userId()), body.password(),
                request.getRemoteAddr(), request, response);
        return me.<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.accepted().body(new SecondStep(true)));
    }

    /** {@code code}: six digits from the authenticator app, or a recovery code (XXXXX-XXXXX). */
    record SecondStepRequest(@NotBlank @Size(max = 20) String code) {
    }

    @PostMapping("/login/two-factor")
    MeResponse logInSecondStep(@Valid @RequestBody SecondStepRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        return auth.completeSecondStep(body.code(), request.getRemoteAddr(), request, response);
    }
}
