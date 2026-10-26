package com.mojud.inventory;

import java.util.UUID;

/** A variant's stock right now. A variant that never had stock is 0 / 0. */
public record StockLevel(UUID variantId, int onHand, int reserved) {

    public int available() {
        return onHand - reserved;
    }
}
