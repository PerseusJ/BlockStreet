package com.perseusj.blockstreet.config;

import org.bukkit.Material;

/**
 * Immutable value object holding the configuration for a single tradeable asset.
 *
 * <p>An instance is created by {@link ConfigManager} for each key under the
 * {@code assets:} section of {@code config.yml} that passes material validation.
 * All fields are set once at load time and read freely from any thread.
 *
 * <h2>Price Tick Invariant</h2>
 * {@code priceTick} is the minimum price increment for this asset. All limit prices
 * submitted to the matching engine are pre-normalised to the nearest multiple of this
 * value via {@link com.perseusj.blockstreet.engine.OrderValidator#normalizeToTick}.
 */
public final class AssetConfig {

    /** The canonical symbol key used throughout the engine (e.g. "DIAMOND"). */
    private final String symbol;

    /** Human-readable display name shown in GUIs. */
    private final String displayName;

    /** The Bukkit {@link Material} used to create and match {@link org.bukkit.inventory.ItemStack}s. */
    private final Material material;

    /** Minimum allowed limit price (inclusive). */
    private final double minPrice;

    /** Maximum allowed limit price (inclusive). */
    private final double maxPrice;

    /** Minimum allowed order quantity (inclusive). */
    private final int minQty;

    /** Maximum allowed order quantity (inclusive). */
    private final int maxQty;

    /**
     * Minimum price increment. All prices in the order book are exact multiples of this.
     * Must be strictly positive.
     */
    private final double priceTick;

    /** Whether this asset is open for trading. Disabled assets reject all new orders. */
    private final boolean enabled;

    // ──────────────────────────── Constructor ────────────────────────────────────

    public AssetConfig(String symbol, String displayName, Material material,
                       double minPrice, double maxPrice,
                       int minQty, int maxQty,
                       double priceTick, boolean enabled) {
        this.symbol      = symbol;
        this.displayName = displayName;
        this.material    = material;
        this.minPrice    = minPrice;
        this.maxPrice    = maxPrice;
        this.minQty      = minQty;
        this.maxQty      = maxQty;
        this.priceTick   = priceTick;
        this.enabled     = enabled;
    }

    // ──────────────────────────── Getters ────────────────────────────────────────

    public String   getSymbol()      { return symbol; }
    public String   getDisplayName() { return displayName; }
    public Material getMaterial()    { return material; }
    public double   getMinPrice()    { return minPrice; }
    public double   getMaxPrice()    { return maxPrice; }
    public int      getMinQty()      { return minQty; }
    public int      getMaxQty()      { return maxQty; }
    public double   getPriceTick()   { return priceTick; }
    public boolean  isEnabled()      { return enabled; }

    @Override
    public String toString() {
        return String.format("AssetConfig{symbol=%s, material=%s, tick=%.4f, enabled=%b}",
                symbol, material, priceTick, enabled);
    }
}
