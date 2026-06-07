package com.perseusj.blockstreet.engine.model;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a single order submitted to the BlockStreet matching engine.
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Immutable identity fields (orderId, playerId, symbol, side, type, limitPrice,
 *       submittedAt) are set once at construction and read freely from any thread.</li>
 *   <li>Mutable state fields (quantityRemaining, status, escrowAmountConsumed) are
 *       mutated <strong>only</strong> on the MatchingEngine dedicated thread, which
 *       serializes all mutations. Reads from the Main Thread (e.g. GUI refresh) use
 *       {@code volatile} to guarantee visibility without stale cache reads.</li>
 *   <li>{@link #assetsLocked} and {@link #consumed} are {@link AtomicBoolean} to allow
 *       lock-free compare-and-set operations during cancellation and fill settlement
 *       across the Main ↔ Engine thread boundary.</li>
 * </ul>
 *
 * <h2>Two-Phase Commit Protocol</h2>
 * <ol>
 *   <li><b>Phase A (Main Thread)</b> — Vault/inventory assets are reserved, then
 *       {@code assetsLocked} is set {@code true} and the order enters the submission queue.</li>
 *   <li><b>Phase B (Engine Thread)</b> — Matches the order; on each fill, dispatches a
 *       {@link com.perseusj.blockstreet.engine.model.TradeMatch} to the Main Thread for settlement.</li>
 *   <li><b>Phase C (Main Thread, via SettlementDispatcher)</b> — Currency and items are
 *       transferred; when the order is terminal (fully filled or cancelled), {@code consumed}
 *       is CAS'd {@code false → true} exactly once.</li>
 * </ol>
 */
public final class Order {

    // ──────────────────────────── Immutable identity ─────────────────────────────

    private final UUID orderId;
    private final UUID playerId;

    /** Canonical symbol key, e.g. "DIAMOND", "NETHERITE_INGOT". */
    private final String symbol;

    private final OrderSide side;
    private final OrderType type;

    /**
     * Normalized limit price (rounded to asset price-tick before book entry).
     * Always {@code 0.0} for MARKET orders.
     */
    private final double limitPrice;

    /**
     * {@link System#nanoTime()} at submission — used as FIFO tiebreaker within a
     * price level when two orders share the same price.
     */
    private final long submittedAt;

    // ──────────────────────────── Mutable state ───────────────────────────────────

    /** Original quantity requested. Never changes after construction. */
    private volatile int quantityOriginal;

    /**
     * Remaining unfilled quantity. Decremented by the engine on each partial fill.
     * Reads from other threads see a consistent value thanks to {@code volatile}.
     */
    private volatile int quantityRemaining;

    /** Current lifecycle status. Written only on the engine thread. */
    private volatile OrderStatus status;

    // ──────────────────────────── Escrow tracking ────────────────────────────────

    /**
     * Total amount of currency locked in escrow during Phase A.
     * Set once on the Main Thread before the order enters the queue; never changes.
     * {@code 0.0} for SELL orders (escrow is in items, not currency).
     */
    private volatile double escrowAmount;

    /**
     * Running tally of escrow consumed by fills so far.
     * Incremented by SettlementDispatcher on each partial fill (Main Thread only).
     * Used to calculate the remaining refund when the order is terminal.
     */
    private volatile double escrowAmountConsumedSoFar;

    // ──────────────────────────── Thread-safety primitives ───────────────────────

    /**
     * Set to {@code true} exactly once on the Main Thread the moment the order's
     * assets (currency/items) are successfully reserved. Guards against the order
     * entering the queue before assets are locked.
     */
    private final AtomicBoolean assetsLocked = new AtomicBoolean(false);

    /**
     * CAS gate: transitions {@code false → true} exactly once when the order reaches
     * a terminal state (filled or cancelled). Both the cancellation handler and the
     * fill settlement path attempt the CAS; only the winner acts. This prevents any
     * double-spend or double-refund scenario.
     */
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    // ──────────────────────────── Sentinel / special orders ──────────────────────

    /**
     * Poison-pill sentinel used to shut down the matching engine gracefully.
     * The engine checks {@code order == SHUTDOWN_SENTINEL} (reference equality)
     * before any other processing.
     */
    public static final Order SHUTDOWN_SENTINEL = new Order();

    /** Private no-arg constructor for the SHUTDOWN_SENTINEL only. */
    private Order() {
        this.orderId   = new UUID(0L, 0L);
        this.playerId  = new UUID(0L, 0L);
        this.symbol    = "__SHUTDOWN__";
        this.side      = OrderSide.BUY;
        this.type      = OrderType.MARKET;
        this.limitPrice = 0.0;
        this.submittedAt = 0L;
        this.quantityOriginal  = 0;
        this.quantityRemaining = 0;
        this.status = OrderStatus.CANCELLED;
    }

    // ──────────────────────────── Constructor ────────────────────────────────────

    /**
     * Constructs a new order.
     *
     * @param orderId        unique order identifier
     * @param playerId       player who submitted the order
     * @param symbol         asset symbol (must match a configured tradeable asset)
     * @param side           BUY or SELL
     * @param type           LIMIT or MARKET
     * @param limitPrice     normalized limit price; must be {@code 0.0} for MARKET orders
     * @param quantityOriginal number of units requested (≥ 1)
     * @param submittedAt    {@link System#nanoTime()} at submission for FIFO priority
     */
    public Order(UUID orderId, UUID playerId, String symbol,
                 OrderSide side, OrderType type,
                 double limitPrice, int quantityOriginal, long submittedAt) {
        this.orderId          = orderId;
        this.playerId         = playerId;
        this.symbol           = symbol;
        this.side             = side;
        this.type             = type;
        this.limitPrice       = limitPrice;
        this.quantityOriginal = quantityOriginal;
        this.quantityRemaining = quantityOriginal;
        this.submittedAt      = submittedAt;
        this.status           = OrderStatus.OPEN;
    }

    // ──────────────────────────── Getters ────────────────────────────────────────

    public UUID getOrderId()           { return orderId; }
    public UUID getPlayerId()          { return playerId; }
    public String getSymbol()          { return symbol; }
    public OrderSide getSide()         { return side; }
    public OrderType getType()         { return type; }
    public double getLimitPrice()      { return limitPrice; }
    public long getSubmittedAt()       { return submittedAt; }
    public int getQuantityOriginal()   { return quantityOriginal; }
    public int getQuantityRemaining()  { return quantityRemaining; }
    public OrderStatus getStatus()     { return status; }
    public double getEscrowAmount()    { return escrowAmount; }
    public double getEscrowAmountConsumedSoFar() { return escrowAmountConsumedSoFar; }

    // ──────────────────────────── Mutation (engine-thread only unless noted) ────

    /** Called by the engine on each partial fill. */
    public void decrementQuantityRemaining(int qty) {
        this.quantityRemaining -= qty;
    }

    /** Called by the engine to update lifecycle status. */
    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    // ──────────────────────────── Escrow management (Main Thread) ────────────────

    /**
     * Stores the escrow amount locked during Phase A (Main Thread only).
     */
    public void setEscrowAmount(double amount) {
        this.escrowAmount = amount;
    }

    /**
     * Atomically increments the consumed escrow counter.
     * Called by SettlementDispatcher on each fill (Main Thread).
     *
     * @param amount portion of escrow used by this fill
     */
    public synchronized void incrementEscrowConsumed(double amount) {
        this.escrowAmountConsumedSoFar += amount;
    }

    // ──────────────────────────── Two-phase commit primitives ────────────────────

    /**
     * Attempts to mark assets as locked. Returns {@code true} if this call was the
     * one to set the flag (i.e., CAS succeeded). Will be {@code false} if already locked.
     */
    public boolean tryLockAssets() {
        return assetsLocked.compareAndSet(false, true);
    }

    /** Returns {@code true} if assets have been successfully locked. */
    public boolean isAssetsLocked() {
        return assetsLocked.get();
    }

    /**
     * Attempts to consume this order (mark as terminal). Exactly one caller will
     * succeed — either the cancellation handler or the final fill settlement path.
     *
     * @return {@code true} if this invocation won the CAS and should proceed with
     *         the terminal action (refund or delivery); {@code false} if another
     *         path already consumed this order.
     */
    public boolean tryConsume() {
        return consumed.compareAndSet(false, true);
    }

    /** Returns {@code true} if this order has been consumed (filled or cancelled). */
    public boolean isConsumed() {
        return consumed.get();
    }

    // ──────────────────────────── Equality / identity ────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return orderId.equals(other.orderId);
    }

    @Override
    public int hashCode() {
        return orderId.hashCode();
    }

    @Override
    public String toString() {
        return String.format("Order{id=%s, player=%s, symbol=%s, side=%s, type=%s, " +
                "limitPrice=%.4f, qty=%d/%d, status=%s}",
                orderId, playerId, symbol, side, type, limitPrice,
                quantityRemaining, quantityOriginal, status);
    }
}
