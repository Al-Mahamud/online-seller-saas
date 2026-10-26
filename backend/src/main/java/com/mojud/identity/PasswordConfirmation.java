package com.mojud.identity;

import java.util.UUID;

/**
 * Asks a signed-in user to re-enter their password before something sensitive, such as moving
 * the account to a new email or handing over a business. Wrong passwords count against the
 * sign-in failure limits, so a stolen session cannot be used to guess it.
 */
public interface PasswordConfirmation {

    /**
     * @throws com.mojud.shared.error.ApiException {@code account.wrong_password}, or
     *         {@code rate_limited} after too many failures
     */
    void confirm(UUID userId, String password, String clientIp);
}
