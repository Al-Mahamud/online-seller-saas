package com.mojud.identity;

import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.phone.PhoneNumber;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Accounts a business creates for its members: the User ID the business chose and a random
 * starting password, which works once, for {@link #STARTING_PASSWORD_LIFETIME}, and must be
 * replaced at the first sign-in. Nothing about the account is verified; the person proves their
 * phone or email later, like anyone else.
 */
public interface TeamAccounts {

    Duration STARTING_PASSWORD_LIFETIME = Duration.ofDays(7);

    record Created(UUID userId, String startingPassword) {
    }

    /**
     * Joins the caller's transaction, so the account and its membership are saved together.
     *
     * @throws com.mojud.shared.error.ApiException {@code account.user_id_taken}, or a validation
     *         error on {@code userId} or {@code name}
     */
    Created create(String userId, String name, PhoneNumber phone, @Nullable EmailAddress email, String locale,
            UUID businessId);

    /**
     * A new starting password for an account {@code businessId} created, which signs it out on every
     * device. Empty when another business, or the person themself, made the account. Call outside a
     * transaction: sessions are changed on their own connection.
     */
    Optional<String> newStartingPassword(UUID userId, UUID businessId);
}
