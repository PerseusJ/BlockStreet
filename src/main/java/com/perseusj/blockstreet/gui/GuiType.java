package com.perseusj.blockstreet.gui;

/**
 * Identifies the type of a BlockStreet GUI session tracked by {@link GuiManager}.
 *
 * <p>Every open {@link GuiSession} carries one of these values so that
 * {@code InventoryClickEvent} and {@code InventoryCloseEvent} handlers can dispatch
 * to the correct GUI class without instanceof checks.
 */
public enum GuiType {

    /** Main 54-slot market screen showing order book depth. */
    MARKET,

    /** 3-row order-entry screen (type / price / qty selection). */
    ORDER_ENTRY,

    /** Paginated list of the player's currently open orders. */
    ACTIVE_ORDERS
}
