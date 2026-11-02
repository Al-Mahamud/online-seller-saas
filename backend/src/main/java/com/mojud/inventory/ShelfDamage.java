package com.mojud.inventory;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Goods damaged or lost on the shelf (a DAMAGE movement). {@code reason} is null for damage
 * recorded before reasons existed; {@code userId} is who recorded it.
 */
public record ShelfDamage(UUID id, Instant at, UUID variantId, int quantity, @Nullable DamageReason reason,
        @Nullable String note, @Nullable UUID userId) {
}
