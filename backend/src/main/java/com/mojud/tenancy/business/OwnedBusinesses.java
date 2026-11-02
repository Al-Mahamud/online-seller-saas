package com.mojud.tenancy.business;

import java.util.UUID;

/**
 * An account owns at most one business; it can still work in others as staff, which isn't counted.
 * A suspended business, or one waiting to be deleted, still counts. Everything that gives an
 * account a business, creating one or receiving one by ownership transfer, checks under this
 * advisory lock, so parallel requests cannot both pass.
 */
public final class OwnedBusinesses {

    private OwnedBusinesses() {
    }

    public static String lockKey(UUID userId) {
        return "owned-businesses:" + userId;
    }
}
