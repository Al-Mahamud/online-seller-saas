package com.mojud.identity.auth;

import com.mojud.identity.account.MeResponse;
import com.mojud.identity.otp.OtpChannel;
import com.mojud.identity.otp.OtpDestination;
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

    /** {@code to} is a phone number or an email address. */
    record CodeRequest(@NotBlank String to, @NotNull OtpPurpose purpose) {
    }

    /** Always 202, whether or not a code was sent (see AuthService#requestCode). */
    @PostMapping("/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestCode(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        LoginIdentifier destination = LoginIdentifier.parse(body.to(), "to");
        auth.requestCode(destination.destination(), body.purpose(), request.getRemoteAddr());
    }

    record SignUpRequest(
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
        return auth.signUp(new AuthService.SignUp(body.name(), phone, email, body.password(), locale,
                body.codeSentTo() == OtpChannel.PHONE, body.code()), request.getRemoteAddr(), request, response);
    }

    record PasswordResetRequest(
            @NotBlank String login,
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
        auth.resetPassword(LoginIdentifier.parse(body.login(), "login"), body.code(), body.newPassword(),
                request.getRemoteAddr());
    }

    /** {@code login} is a phone number or an email address; give a password or a sign-in code. */
    record LoginRequest(
            @NotBlank String login,
            @Size(max = 128) String password,
            @Pattern(regexp = SIX_DIGITS, message = "{field.six_digit_code}") String code) {
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
        LoginIdentifier login = LoginIdentifier.parse(body.login(), "login");
        boolean hasPassword = body.password() != null && !body.password().isEmpty();
        if (hasPassword == (body.code() != null)) {
            throw ApiException.invalidField("password", "field.password_or_code");
        }
        Optional<MeResponse> me = hasPassword
                ? auth.logInWithPassword(login, body.password(), request.getRemoteAddr(), request, response)
                : auth.logInWithCode(login, body.code(), request.getRemoteAddr(), request, response);
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
