package com.perseusj.blockstreet.engine.book;

import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderSide;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The central in-memory order book for a single tradeable asset symbol.
 *
 * <h2>Data Structures</h2>
 * <ul>
 *   <li><b>Bids</b> (buy orders): {@link ConcurrentSkipListMap} with <em>descending</em>
 *       key order — the best (highest) bid is always at {@code firstKey()}.</li>
 *   <li><b>Asks</b> (sell orders): {@link ConcurrentSkipListMap} with <em>natural ascending</em>
 *       key order — the best (lowest) ask is always at {@code firstKey()}.</li>
 * </ul>
 *
 * <h2>Thread Safety Model</h2>
 * <ul>
 *   <li>All structural mutations (adding/removing price levels and orders) are performed
 *       <strong>exclusively on the MatchingEngine thread</strong>. This eliminates the
 *       need for additional locking on the deque operations inside each {@link PriceLevel}.</li>
 *   <li>{@link ConcurrentSkipListMap} is lock-free for concurrent reads, so the Main Thread
 *       can safely call {@link #getDepthSnapshot()} (volatile read) without any lock.</li>
 *   <li>Running stats ({@code lastTradePrice}, {@code lastTradeTimestamp},
 *       {@code totalVolume24h}) are {@code volatile} for visibility across the
 *       Engine → Main Thread boundary used by the GUI refresh loop.</li>
 *   <li>{@link #depthSnapshot} is a {@code volatile} reference replaced atomically after
 *       each engine cycle — GUI threads always see either the previous complete snapshot
 *       or the new complete snapshot, never a partial update.</li>
 * </ul>
 *
 * <h2>Price Invariant</h2>
 * All keys in both maps are guaranteed to be exact multiples of the asset's configured
 * {@code price-tick}. This invariant is enforced by {@code OrderValidator.normalizeToTick()}
 * before any order enters the submission queue.
 */
public final class OrderBook {

    /** Maximum number of depth levels included in the snapshot (configurable per asset). */
    private static final int DEFAULT_DEPTH_LEVELS = 5;

    private final String symbol;
    private final int depthLevels;

    /**
     * Bid side: key = price (descending — highest price = best bid = firstKey).
     * Value = FIFO queue of orders at that price.
     */
    private final ConcurrentSkipListMap<Double, PriceLevel> bids =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    /**
     * Ask side: key = price (ascending — lowest price = best ask = firstKey).
     * Value = FIFO queue of orders at that price.
     */
    private final ConcurrentSkipListMap<Double, PriceLevel> asks =
            new ConcurrentSkipListMap<>();

    // ──────────────────────────── Volatile stats ─────────────────────────────────

    private volatile double lastTradePrice = 0.0;
    private volatile long lastTradeTimestamp = 0L;
    private volatile long totalVolume24h = 0L;

    /** Atomically replaced after each engine cycle. GUI reads this reference. */
    private volatile MarketDepthSnapshot depthSnapshot;

    // ──────────────────────────── Constructor ────────────────────────────────────

    public OrderBook(String symbol) {
        this(symbol, DEFAULT_DEPTH_LEVELS);
    }

    public OrderBook(String symbol, int depthLevels) {
        this.symbol      = symbol;
        this.depthLevels = depthLevels;
        this.depthSnapshot = MarketDepthSnapshot.emptyFor(symbol);
    }

    // ──────────────────────────── Public API (engine thread only) ────────────────

    /**
     * Returns the ask-side map for iteration by the matching engine.
     * <b>Engine thread only.</b>
     */
    public ConcurrentSkipListMap<Double, PriceLevel> getAsks() { return asks; }

    /**
     * Returns the bid-side map for iteration by the matching engine.
     * <b>Engine thread only.</b>
     */
    public ConcurrentSkipListMap<Double, PriceLevel> getBids() { return bids; }

    /**
     * Places a resting order into the appropriate side of the book.
     *
     * <p>Creates a new {@link PriceLevel} at the given price if one does not already exist.
     * Uses {@code computeIfAbsent} which is atomic on {@link ConcurrentSkipListMap}.
     *
     * <p><b>Engine thread only.</b>
     *
     * @param order the order to rest in the book (must have a valid limitPrice)
     */
    public void restOrder(Order order) {
        double price = order.getLimitPrice();
        if (order.getSide() == OrderSide.BUY) {
            bids.computeIfAbsent(price, PriceLevel::new).addOrder(order);
        } else {
            asks.computeIfAbsent(price, PriceLevel::new).addOrder(order);
        }
    }

    /**
     * Removes an order from the book by scanning for it at its known price level.
     * Used by the cancellation handler. Returns {@code true} if the order was found
     * and removed; {@code false} if it had already been filled or moved.
     *
     * <p><b>Engine thread only.</b>
     */
    public boolean removeOrder(Order order) {
        ConcurrentSkipListMap<Double, PriceLevel> side =
                (order.getSide() == OrderSide.BUY) ? bids : asks;
        PriceLevel level = side.get(order.getLimitPrice());
        if (level == null) return false;
        boolean removed = level.removeOrder(order);
        if (level.isEmpty()) {
            side.remove(order.getLimitPrice());
        }
        return removed;
    }

    /**
     * Rebuilds and atomically publishes the market depth snapshot.
     * Called by the engine after every processed order.
     *
     * <p>Reads the top-N entries from each side using iterator (snapshot-consistent on
     * {@link ConcurrentSkipListMap}). Calculates spread. Replaces {@link #depthSnapshot}
     * with a new immutable value object.
     *
     * <p><b>Engine thread only</b> for the write; Main Thread reads via volatile.
     */
    public void rebuildDepthSnapshot() {
        List<MarketDepthSnapshot.DepthLevel> bidLevels = buildDepthLevels(bids);
        List<MarketDepthSnapshot.DepthLevel> askLevels = buildDepthLevels(asks);

        double spread = 0.0;
        if (!bidLevels.isEmpty() && !askLevels.isEmpty()) {
            spread = askLevels.get(0).price() - bidLevels.get(0).price();
        }

        this.depthSnapshot = new MarketDepthSnapshot(
                symbol,
                Collections.unmodifiableList(bidLevels),
                Collections.unmodifiableList(askLevels),
                spread,
                lastTradePrice,
                lastTradeTimestamp,
                totalVolume24h
        );
    }

    /**
     * Iterates the top-N entries of a side map and builds depth level records.
     */
    private List<MarketDepthSnapshot.DepthLevel> buildDepthLevels(
            ConcurrentSkipListMap<Double, PriceLevel> side) {
        List<MarketDepthSnapshot.DepthLevel> result = new ArrayList<>(depthLevels);
        int count = 0;
        for (Map.Entry<Double, PriceLevel> entry : side.entrySet()) {
            if (count >= depthLevels) break;
            PriceLevel level = entry.getValue();
            if (!level.isEmpty()) {
                result.add(new MarketDepthSnapshot.DepthLevel(entry.getKey(), level.totalVolume()));
                count++;
            }
        }
        return result;
    }

    // ──────────────────────────── Thread-safe stat updates (engine thread) ───────

    /**
     * Updates the last trade price and timestamp after a fill.
     * {@code volatile} writes ensure the GUI refresh loop on the Main Thread
     * sees fresh values on the next read.
     *
     * <p><b>Engine thread only.</b>
     */
    public void recordTrade(double executionPrice, int quantityFilled) {
        this.lastTradePrice     = executionPrice;
        this.lastTradeTimestamp = System.currentTimeMillis();
        this.totalVolume24h    += quantityFilled;
    }

    // ──────────────────────────── Cross-thread reads ──────────────────────────────

    /**
     * Returns the latest published market depth snapshot.
     * Safe to call from any thread — {@code volatile} reference guarantees
     * the returned object is the most recently replaced complete snapshot.
     */
    public MarketDepthSnapshot getDepthSnapshot() {
        return depthSnapshot;
    }

    public String getSymbol()             { return symbol; }
    public double getLastTradePrice()     { return lastTradePrice; }
    public long getLastTradeTimestamp()   { return lastTradeTimestamp; }
    public long getTotalVolume24h()       { return totalVolume24h; }

    /**
     * Returns a flat list of all orders currently resting in the book (both sides).
     * Used by {@code onDisable()} to persist state before shutdown.
     *
     * <p><b>Engine thread only.</b>
     */
    public List<Order> getAllOrders() {
        List<Order> result = new ArrayList<>();
        for (PriceLevel level : bids.values()) {
            Order o;
            // Drain-free iteration: peek at head, iterate deque
            for (Order order : getOrdersFromLevel(level)) result.add(order);
        }
        for (PriceLevel level : asks.values()) {
            for (Order order : getOrdersFromLevel(level)) result.add(order);
        }
        return result;
    }

    private List<Order> getOrdersFromLevel(PriceLevel level) {
        // We reconstruct by using peekAfter to traverse without polling
        List<Order> result = new ArrayList<>();
        Order current = level.peekBest();
        while (current != null) {
            result.add(current);
            current = level.peekAfter(current);
        }
        return result;
    }
}
