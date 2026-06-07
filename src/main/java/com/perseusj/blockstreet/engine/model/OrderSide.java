package com.perseusj.blockstreet.engine.model;

/**
 * The side of an order: whether the player is buying or selling.
 */
public enum OrderSide {
    /** Bid — player is willing to pay UP TO their limit price. */
    BUY,
    /** Ask — player is willing to accept AT LEAST their limit price. */
    SELL
}
