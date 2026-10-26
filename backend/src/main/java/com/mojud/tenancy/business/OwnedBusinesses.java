package com.mojud.tenancy.business;

import java.util.UUID;

/**
 * A user may own a limited number of businesses ({@code mojud.tenancy.max-owned-businesses}).
 * Everything that adds one, creating a business or receiving one by ownership transfer, counts
 * under this advisory lock, so parallel requests cannot pass the limit together.
 */
public final class OwnedBusinesses {

    private OwnedBusinesses() {
    }

    public static String lockKey(UUID userId) {
        return "owned-businesses:" + userId;
    }
}
