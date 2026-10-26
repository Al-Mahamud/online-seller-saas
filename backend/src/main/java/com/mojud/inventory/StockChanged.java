package com.mojud.inventory;

import java.util.List;
import java.util.UUID;

/**
 * Published by {@link StockService} after every change, inside its transaction, so listeners (the
 * catalog's low-stock alerts) act in the same transaction as the change.
 */
public record StockChanged(MovementType type, List<Change> changes) {

    public record Change(UUID variantId, int availableBefore, int availableAfter) {

        public boolean lowered() {
            return availableAfter < availableBefore;
        }
    }
}
