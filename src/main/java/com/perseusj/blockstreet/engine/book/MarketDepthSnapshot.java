package com.perseusj.blockstreet.engine.book;

import java.util.Collections;
import java.util.List;

/**
 * An immutable, point-in-time snapshot of the top-N bid and ask price levels.
 *
 * <p>Produced by the matching engine after every processed order and stored as a
 * {@code volatile} reference on {@link OrderBook}. GUI threads read this reference
 * atomically without any locking — they never see a partially-updated snapshot.
 *
 * @param symbol    asset symbol this snapshot belongs to
 * @param bids      top-N bid levels in descending price order (best bid first)
 * @param asks      top-N ask levels in ascending price order (best ask first)
 * @param spread    difference between best ask and best bid; {@code 0.0} if either side is empty
 * @param lastTradePrice most recent execution price, or {@code 0.0} if no trades have occurred
 * @param lastTradeTimestamp epoch millis of the last trade, or {@code 0L}
 * @param totalVolume24h running 24-hour fill volume in units
 */
public record MarketDepthSnapshot(
        String symbol,
        List<DepthLevel> bids,
        List<DepthLevel> asks,
        double spread,
        double lastTradePrice,
        long lastTradeTimestamp,
        long totalVolume24h
) {

    /**
     * A single aggregated price level within the snapshot.
     *
     * @param price       normalized price for this level
     * @param totalVolume total quantity resting at this price
     */
    public record DepthLevel(double price, int totalVolume) {}

    /**
     * Empty snapshot returned before any orders have been processed.
     * GUI code should treat {@code lastTradePrice == 0.0} as "no data yet."
     */
    public static final MarketDepthSnapshot EMPTY = new MarketDepthSnapshot(
            "UNKNOWN",
            Collections.emptyList(),
            Collections.emptyList(),
            0.0, 0.0, 0L, 0L
    );

    /**
     * Convenience factory: creates an EMPTY snapshot for the given symbol.
     */
    public static MarketDepthSnapshot emptyFor(String symbol) {
        return new MarketDepthSnapshot(symbol,
                Collections.emptyList(), Collections.emptyList(),
                0.0, 0.0, 0L, 0L);
    }

    /** @return {@code true} if there are no resting bids. */
    public boolean hasBids() { return !bids.isEmpty(); }

    /** @return {@code true} if there are no resting asks. */
    public boolean hasAsks() { return !asks.isEmpty(); }

    /**
     * Best (highest) bid price, or {@code 0.0} if no bids exist.
     */
    public double bestBid() {
        return bids.isEmpty() ? 0.0 : bids.get(0).price();
    }

    /**
     * Best (lowest) ask price, or {@code 0.0} if no asks exist.
     */
    public double bestAsk() {
        return asks.isEmpty() ? 0.0 : asks.get(0).price();
    }
}
