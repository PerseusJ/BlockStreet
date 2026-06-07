package com.perseusj.blockstreet.engine.model;

/**
 * Defines whether an order rests in the book or sweeps aggressively.
 */
public enum OrderType {
    /** Rests in the order book until filled or explicitly cancelled. */
    LIMIT,
    /** Sweeps the book at the best available price; does not rest. */
    MARKET
}
