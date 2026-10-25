package com.mojud.identity.account;

public enum UserStatus {
    ACTIVE,
    /** Cannot sign in. Set by platform admins (Phase 12a). */
    DISABLED
}
