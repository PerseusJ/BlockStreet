package com.perseusj.blockstreet.engine.model;

/**
 * Lifecycle state of an order from submission through terminal resolution.
 */
public enum OrderStatus {
    /** Resting in the order book, awaiting a match. */
    OPEN,
    /** Partially executed; still resting with remaining quantity. */
    PARTIALLY_FILLED,
    /** Fully executed — all quantity has been matched. */
    FILLED,
    /** Explicitly cancelled by the player or by the engine (e.g., market order timeout). */
    CANCELLED,
    /** Rejected before entering the book (insufficient funds/items, invalid parameters). */
    REJECTED,
    /** Order reached its duration limit; assets dispatched to Mailbox. */
    EXPIRED
}
