package com.mojud.identity;

import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The signed-in user, as stored in the session. Controllers receive it with
 * {@code @AuthenticationPrincipal AuthenticatedUser user}.
 *
 * <p>{@link #getName()} is the user id, which is what Spring Session indexes sessions by, so all
 * sessions of a user can be found and revoked together. Phase 2a adds the selected business to the
 * session separately.
 */
public record AuthenticatedUser(UUID id, boolean platformAdmin) implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return id.toString();
    }
}
