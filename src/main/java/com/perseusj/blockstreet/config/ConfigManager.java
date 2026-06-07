package com.perseusj.blockstreet.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads, validates, and exposes all BlockStreet configuration.
 *
 * <p>Backed by Bukkit's {@link FileConfiguration} (config.yml). Must be initialized
 * on the <strong>Main Thread</strong> during plugin enable and on every
 * {@code /bs admin reload} call.
 *
 * <h2>Hot Reload</h2>
 * {@link #load()} is idempotent and safe to call multiple times. Existing references to
 * {@link AssetConfig} objects become stale after a reload — callers should always fetch
 * fresh values via {@link #getAsset(String)}.
 *
 * <h2>Thread Safety</h2>
 * The internal map is replaced atomically (volatile reference). Reads from other threads
 * (e.g. MatchingEngine taker-fee read) are safe due to {@code volatile} on the scalar
 * fields.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private final Logger     logger;

    // ──────────────────────────── Volatile config state ─────────────────────────

    /** Map of symbol → AssetConfig. Replaced atomically on each reload. */
    private volatile Map<String, AssetConfig> tradableAssets = Collections.emptyMap();

    /** Taker fee rate as a decimal fraction (e.g. 0.01 for 1%). */
    private volatile double takerFeeRate = 0.01;

    /** How many ticks between GUI re-renders (default 10 = 0.5 s). */
    private volatile int guiRefreshTicks = 10;

    /** DB write flush interval in seconds. */
    private volatile int dbWriteIntervalSeconds = 3;

    /** Maximum resting order age in hours before auto-cancellation. */
    private volatile int orderExpiryHours = 72;

    /** Maximum simultaneous open orders a single player may hold. */
    private volatile int maxOpenOrdersPerPlayer = 10;

    /** Number of depth levels shown in the GUI. */
    private volatile int marketDepthLevels = 5;

    /** Currency symbol displayed in GUI (e.g. "$"). */
    private volatile String currencySymbol = "$";

    // ──────────────────────────── Constructor ────────────────────────────────────

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // ──────────────────────────── Load / Reload ───────────────────────────────────

    /**
     * Reads config.yml and populates all settings. Must be called on the Main Thread.
     * Safe to call multiple times (idempotent — replaces all internal state).
     */
    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // ── Engine settings ────────────────────────────────────────────────────
        takerFeeRate             = cfg.getDouble("engine.taker-fee-percentage", 1.0) / 100.0;
        guiRefreshTicks          = cfg.getInt("engine.gui-refresh-ticks", 10);
        dbWriteIntervalSeconds   = cfg.getInt("engine.db-write-interval-seconds", 3);
        orderExpiryHours         = cfg.getInt("engine.order-expiry-hours", 72);
        maxOpenOrdersPerPlayer   = cfg.getInt("engine.max-open-orders-per-player", 10);

        // ── GUI settings ───────────────────────────────────────────────────────
        marketDepthLevels = cfg.getInt("gui.market-depth-levels", 5);
        currencySymbol    = cfg.getString("gui.currency-symbol", "$");

        // ── Asset configs ──────────────────────────────────────────────────────
        Map<String, AssetConfig> newAssets = new LinkedHashMap<>();
        ConfigurationSection assetsSection = cfg.getConfigurationSection("assets");
        if (assetsSection == null) {
            logger.warning("[ConfigManager] No 'assets' section found in config.yml. " +
                    "No tradeable assets will be available.");
        } else {
            for (String key : assetsSection.getKeys(false)) {
                AssetConfig asset = parseAsset(key, assetsSection.getConfigurationSection(key));
                if (asset != null) {
                    newAssets.put(key, asset);
                }
            }
        }

        tradableAssets = Collections.unmodifiableMap(newAssets);
        logger.info(String.format("[ConfigManager] Loaded %d tradeable asset(s). TakerFeeRate=%.2f%%",
                tradableAssets.size(), takerFeeRate * 100));
    }

    /**
     * Parses a single asset entry from the config section. Returns {@code null} and logs a
     * warning if the entry is missing required fields or references an invalid material.
     */
    private AssetConfig parseAsset(String symbol, ConfigurationSection section) {
        if (section == null) {
            logger.warning("[ConfigManager] Asset '" + symbol + "' has no configuration block.");
            return null;
        }

        String materialName = section.getString("material");
        if (materialName == null) {
            logger.warning("[ConfigManager] Asset '" + symbol + "' is missing 'material'.");
            return null;
        }

        Material mat = Material.matchMaterial(materialName);
        if (mat == null) {
            logger.warning("[ConfigManager] Unknown material '" + materialName +
                    "' for asset '" + symbol + "'. Skipping.");
            return null;
        }

        double priceTick = section.getDouble("price-tick", 0.01);
        if (priceTick <= 0) {
            logger.warning("[ConfigManager] price-tick for '" + symbol +
                    "' must be positive. Defaulting to 0.01.");
            priceTick = 0.01;
        }

        return new AssetConfig(
                symbol,
                section.getString("display-name", symbol),
                mat,
                section.getDouble("min-price", 0.01),
                section.getDouble("max-price", 1_000_000.0),
                section.getInt("min-qty", 1),
                section.getInt("max-qty", 1000),
                priceTick,
                section.getBoolean("enabled", true)
        );
    }

    // ──────────────────────────── Accessors ─────────────────────────────────────

    /**
     * Returns the {@link AssetConfig} for the given symbol, or {@code null} if the symbol
     * is not configured or disabled.
     *
     * <p>Safe to call from any thread (volatile map reference read).
     */
    public AssetConfig getAsset(String symbol) {
        return tradableAssets.get(symbol);
    }

    /**
     * Returns an unmodifiable view of all tradeable assets.
     *
     * <p>Safe to call from any thread.
     */
    public Map<String, AssetConfig> getAllAssets() {
        return tradableAssets;
    }

    /**
     * Returns {@code true} if the symbol is configured and currently enabled for trading.
     */
    public boolean isTradeableSymbol(String symbol) {
        AssetConfig cfg = tradableAssets.get(symbol);
        return cfg != null && cfg.isEnabled();
    }

    // ──────────────────────────── Scalar getters (volatile reads) ───────────────

    /** Taker fee rate as a decimal fraction (e.g. {@code 0.01} for 1%). */
    public double getTakerFeeRate()          { return takerFeeRate; }

    /** GUI re-render interval in Bukkit ticks (default 10). */
    public int getGuiRefreshTicks()          { return guiRefreshTicks; }

    /** DB write flush interval in seconds (default 3). */
    public int getDbWriteIntervalSeconds()   { return dbWriteIntervalSeconds; }

    /** Maximum resting order age in hours before auto-cancellation. */
    public int getOrderExpiryHours()         { return orderExpiryHours; }

    /** Maximum simultaneous open orders per player. */
    public int getMaxOpenOrdersPerPlayer()   { return maxOpenOrdersPerPlayer; }

    /** Number of bid/ask levels shown in the depth GUI. */
    public int getMarketDepthLevels()        { return marketDepthLevels; }

    /** Currency symbol used in GUI text (e.g. "$"). */
    public String getCurrencySymbol()        { return currencySymbol; }
}
