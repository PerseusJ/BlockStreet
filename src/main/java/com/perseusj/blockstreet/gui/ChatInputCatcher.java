package com.perseusj.blockstreet.gui;

import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderType;

import java.util.UUID;

/**
 * Tracks the state of a multi-step chat-based number-entry session for a player
 * placing an order via the BlockStreet GUI.
 *
 * <h2>Steps</h2>
 * <ol>
 *   <li>{@link Step#PRICE} — collect the limit price (LIMIT orders only;
 *       skipped for MARKET orders).</li>
 *   <li>{@link Step#QUANTITY} — collect the quantity.</li>
 * </ol>
 *
 * <p>This is a <em>data-only</em> container.  All event handling logic lives in
 * {@link com.perseusj.blockstreet.listeners.GuiListener} (the
 * {@code AsyncPlayerChatEvent} handler) and in {@link GuiManager}.  Separating
 * state from behaviour allows the catcher to be stored in a simple map keyed on
 * player UUID without coupling to Bukkit registration.
 *
 * <h2>Thread Safety</h2>
 * A {@code ChatInputCatcher} is created on the Main Thread and read on the
 * async chat thread. All fields are {@code volatile} or effectively immutable
 * (set once and never changed after that), so no synchronisation is needed.
 */
public final class ChatInputCatcher {

    /** The two sequential steps required to build an order from chat input. */
    public enum Step {
        /** Awaiting a limit price from the player. */
        PRICE,
        /** Awaiting a quantity from the player. */
        QUANTITY
    }

    // ─────────────────────────── Immutable identity ───────────────────────────────

    /** Player this catcher belongs to. */
    public final UUID      playerId;
    /** Asset symbol (e.g. "DIAMOND"). */
    public final String    symbol;
    /** BUY or SELL. */
    public final OrderSide side;
    /** LIMIT or MARKET. */
    public final OrderType orderType;
    /** Epoch millis when this catcher expires (player did nothing for 30 s). */
    public final long      expiryMillis;

    // ─────────────────────────── Mutable step state ───────────────────────────────

    /** Current step in the conversation. Advances from PRICE → QUANTITY (or QUANTITY directly for MARKET). */
    public volatile Step   step;
    /** Collected limit price (0.0 until the player enters it). */
    public volatile double tempPrice;
    /** Collected quantity (0 until the player enters it). */
    public volatile int    tempQty;

    // ─────────────────────────── Constructor ──────────────────────────────────────

    /**
     * Creates a new catcher ready to collect input for the specified order.
     *
     * @param playerId   player UUID
     * @param symbol     asset symbol
     * @param side       BUY or SELL
     * @param orderType  LIMIT or MARKET
     * @param timeoutMs  milliseconds before this catcher expires (typically 30 000)
     */
    public ChatInputCatcher(UUID playerId, String symbol,
                            OrderSide side, OrderType orderType, long timeoutMs) {
        this.playerId     = playerId;
        this.symbol       = symbol;
        this.side         = side;
        this.orderType    = orderType;
        this.expiryMillis = System.currentTimeMillis() + timeoutMs;

        // MARKET orders skip the PRICE step
        this.step = (orderType == OrderType.LIMIT) ? Step.PRICE : Step.QUANTITY;
    }

    // ─────────────────────────── State helpers ─────────────────────────────────────

    /**
     * Returns {@code true} if this catcher has expired (player did not respond in time).
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiryMillis;
    }

    /**
     * Returns {@code true} if the catcher is waiting for a price (never true for MARKET orders).
     */
    public boolean isAwaitingPrice() {
        return step == Step.PRICE;
    }

    /**
     * Returns {@code true} if the catcher is waiting for a quantity.
     */
    public boolean isAwaitingQuantity() {
        return step == Step.QUANTITY;
    }

    /**
     * Stores the collected price and advances to the QUANTITY step.
     *
     * @param price validated limit price
     */
    public void recordPrice(double price) {
        this.tempPrice = price;
        this.step      = Step.QUANTITY;
    }

    /**
     * Stores the collected quantity (the final step).
     *
     * @param qty validated quantity
     */
    public void recordQuantity(int qty) {
        this.tempQty = qty;
    }

    @Override
    public String toString() {
        return "ChatInputCatcher{player=" + playerId
                + ", symbol=" + symbol
                + ", side=" + side
                + ", type=" + orderType
                + ", step=" + step
                + ", price=" + tempPrice
                + ", qty=" + tempQty + "}";
    }
}
