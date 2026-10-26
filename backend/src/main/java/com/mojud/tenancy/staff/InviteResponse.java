package com.mojud.tenancy.staff;

import com.mojud.tenancy.BusinessRole;
import java.time.Instant;
import java.util.UUID;

/** An open invite as managers see it; the token is never shown again after it is sent. */
record InviteResponse(UUID id, String to, BusinessRole role, Instant expiresAt) {

    static InviteResponse of(Invite invite) {
        return new InviteResponse(invite.getId(), invite.getDestination(), invite.getRole(), invite.getExpiresAt());
    }
}
