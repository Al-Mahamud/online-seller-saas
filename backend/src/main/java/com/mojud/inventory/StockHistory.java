package com.mojud.inventory;

import java.time.Instant;
import java.util.UUID;

/** One entry of a variant's stock history, with the level right after it. */
public record StockHistory(UUID id, Instant at, MovementType type, int onHandChange, int reservedChange,
        int onHandAfter, int reservedAfter, String referenceType, UUID referenceId, String note, UUID userId) {

    public int availableAfter() {
        return onHandAfter - reservedAfter;
    }
}
