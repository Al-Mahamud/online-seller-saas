package com.mojud.orders;

/**
 * What an order has done to stock (V22), so a cancel or an edit undoes exactly that, whatever the
 * business's stock_commit_point is by then.
 */
enum StockState {
    /** A draft, or a cancelled order: holds nothing. */
    NONE,
    /** Confirmed with stock taken at shipping: its items are reserved. */
    RESERVED,
    /** Its items have left on_hand (confirmed with stock taken at confirmation; or shipped, Phase 8). */
    DEDUCTED
}
