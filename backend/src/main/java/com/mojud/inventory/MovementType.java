package com.mojud.inventory;

/**
 * Kinds of stock movement and their effect (backend-plan.md §5.2). {@code on hand} is what is on
 * the shelf; {@code reserved} is held for confirmed orders; {@code available} = on hand - reserved.
 */
public enum MovementType {
    /** +on hand: stock entered with a new product (or by import). */
    OPENING,
    /** +on hand: new stock arrived. */
    RECEIVE,
    /** ±on hand: a count correction. */
    ADJUST,
    /** -on hand: damaged or lost on the shelf. */
    DAMAGE,
    /** +reserved: an order was confirmed (stock_commit_point SHIP). */
    RESERVE,
    /** -reserved: a reservation ended without the goods leaving (cancelled, item removed). */
    RELEASE,
    /** -on hand, -reserved: handed to the courier. */
    SHIP,
    /** -on hand: an order was confirmed when the business deducts at confirm. */
    SALE,
    /** +on hand: such an order was cancelled before shipping. */
    UNSALE,
    /** +on hand: a returned item went back on the shelf. */
    RETURN,
    /** No change: a returned item was written off (recorded for reports). */
    RETURN_DAMAGED
}
