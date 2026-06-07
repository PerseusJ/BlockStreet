package com.perseusj.blockstreet.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Validates and normalizes order parameters before they are locked and submitted
 * to the matching engine.
 *
 * <p>All methods in this class operate on the <strong>Main Thread</strong> during Phase 2A
 * (pre-submission asset locking). None of these methods touch the order book or engine
 * thread — they are purely computational and fully unit-testable without any Bukkit context.
 *
 * <h2>Price Tick Normalization</h2>
 * Every limit price must be a strict multiple of the asset's configured {@code price-tick}
 * before entering the order book. This prevents the {@code ConcurrentSkipListMap} from
 * accumulating thousands of micro-fragmented price levels (e.g. {@code $10.001, $10.002})
 * instead of a single clean level at {@code $10.00}.
 *
 * <p>The normalization is performed using {@link BigDecimal} arithmetic to avoid IEEE 754
 * floating-point drift that would accumulate over repeated divisions and multiplications
 * with small tick values like {@code 0.01}.
 */
public final class OrderValidator {

    private OrderValidator() {} // Utility class — no instantiation

    // ──────────────────────────── Price Tick Normalization ───────────────────────

    /**
     * Normalizes a raw price to the nearest valid tick for the given asset.
     *
     * <p>Formula: {@code P_normalized = round(P_raw / T) * T}
     * where rounding mode is {@link RoundingMode#HALF_UP} (intuitive for players).
     *
     * <p>Example: {@code normalizeToTick(10.007, 0.01) → 10.01}
     *
     * <p>Must be called <strong>before</strong> Phase 2A asset locking and before
     * {@code submissionQueue.offer()}. Operates on the Main Thread.
     *
     * @param rawPrice  the price entered by the player (may have arbitrary decimal places)
     * @param tickSize  the asset's configured minimum price increment (e.g. 0.01)
     * @return the normalized price, rounded to the nearest valid tick
     * @throws IllegalArgumentException if {@code tickSize} ≤ 0
     */
    public static double normalizeToTick(double rawPrice, double tickSize) {
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tickSize must be positive, got: " + tickSize);
        }
        // Use BigDecimal to avoid IEEE 754 floating-point drift compounding over many ticks
        BigDecimal raw        = BigDecimal.valueOf(rawPrice);
        BigDecimal tick       = BigDecimal.valueOf(tickSize);
        BigDecimal normalized = raw.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick);
        return normalized.doubleValue();
    }

    // ──────────────────────────── Validation Helpers ─────────────────────────────

    /**
     * Returns a validation error message if the limit price is out of range,
     * or {@code null} if the price is valid.
     *
     * @param price    the (already normalized) limit price
     * @param minPrice minimum allowed price from asset config
     * @param maxPrice maximum allowed price from asset config
     * @return an error reason string, or {@code null} if valid
     */
    public static String validatePrice(double price, double minPrice, double maxPrice) {
        if (price <= 0) {
            return "Price must be greater than zero.";
        }
        if (price < minPrice) {
            return String.format("Price %.4f is below the minimum of %.4f.", price, minPrice);
        }
        if (price > maxPrice) {
            return String.format("Price %.4f exceeds the maximum of %.4f.", price, maxPrice);
        }
        return null; // valid
    }

    /**
     * Returns a validation error message if the order quantity is invalid,
     * or {@code null} if the quantity is valid.
     *
     * @param quantity the requested quantity
     * @param minQty   minimum allowed quantity from asset config
     * @param maxQty   maximum allowed quantity from asset config
     * @return an error reason string, or {@code null} if valid
     */
    public static String validateQuantity(int quantity, int minQty, int maxQty) {
        if (quantity <= 0) {
            return "Quantity must be at least 1.";
        }
        if (quantity < minQty) {
            return String.format("Quantity %d is below the minimum of %d.", quantity, minQty);
        }
        if (quantity > maxQty) {
            return String.format("Quantity %d exceeds the maximum of %d.", quantity, maxQty);
        }
        return null; // valid
    }

    /**
     * Returns {@code true} if the given symbol is the reserved shutdown sentinel symbol.
     * Guards against accidental submission of internal sentinel orders.
     */
    public static boolean isSystemSymbol(String symbol) {
        return "__SHUTDOWN__".equals(symbol);
    }
}
