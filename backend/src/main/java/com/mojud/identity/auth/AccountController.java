package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.identity.account.MeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own account. */
@RestController
@RequestMapping("/api/v1/me")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return accounts.me(user.id());
    }

    /** Fields left out stay as they are. */
    record UpdateRequest(
            /** Checked for blankness after trimming, by the account (User#cleanName). */
            @Size(min = 1, max = 120) String name,
            @Pattern(regexp = "bn|en", message = "{field.locale_invalid}") String locale) {
    }

    @PatchMapping
    MeResponse update(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody UpdateRequest body) {
        return accounts.update(user.id(), body.name(), body.locale());
    }

    record PasswordChange(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    /** Signs out every other device. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody PasswordChange body,
            HttpServletRequest request) {
        accounts.changePassword(user.id(), body.currentPassword(), body.newPassword(),
                request.getSession().getId(), request.getRemoteAddr());
    }

    /** {@code to} is a phone number or an email address. */
    record ContactCodeRequest(@NotBlank String to) {
    }

    /** Always 202 (see AccountService#requestContactCode). */
    @PostMapping("/contact/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestContactCode(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ContactCodeRequest body, HttpServletRequest request) {
        accounts.requestContactCode(user.id(), LoginIdentifier.parse(body.to(), "to").destination(),
                request.getRemoteAddr());
    }

    record ContactConfirmation(
            @NotBlank String to,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "{field.six_digit_code}") String code,
            @NotBlank String currentPassword) {
    }

    /** Adds, proves or replaces the account's phone or email. */
    @PostMapping("/contact")
    MeResponse confirmContact(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ContactConfirmation body, HttpServletRequest request) {
        return accounts.confirmContact(user.id(), LoginIdentifier.parse(body.to(), "to").destination(), body.code(),
                body.currentPassword(), request.getRemoteAddr());
    }
}
