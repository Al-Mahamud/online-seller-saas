package com.mojud.tenancy.system;

import com.mojud.shared.db.system.SystemDatabase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Which business an invite link belongs to. The link carries only a token, and row-level security
 * would need the business first; everything else about the invite is read inside that business.
 */
@Component
public class InviteDirectory {

    private final SystemDatabase database;

    InviteDirectory(SystemDatabase database) {
        this.database = database;
    }

    public Optional<UUID> businessOfToken(String tokenHash) {
        return database.jdbc().sql("select business_id from invites where token_hash = ?")
                .param(tokenHash)
                .query(UUID.class)
                .optional();
    }
}
