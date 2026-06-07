package com.perseusj.blockstreet.gui;

import com.perseusj.blockstreet.engine.book.MarketDepthSnapshot;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link MarketDepthSnapshot} data into Bukkit {@link ItemStack}s for
 * rendering inside the {@link MarketGui}.
 *
 * <h2>Design</h2>
 * This class is stateless and all methods are static helpers. It is intentionally
 * separated from {@link MarketGui} so that rendering logic can be tested and
 * evolved independently of the GUI layout.
 *
 * <h2>Thread Safety</h2>
 * All methods must be called from the <strong>Server Main Thread</strong> because
 * {@link ItemMeta} construction touches Bukkit internals.
 */
public final class DepthRenderer {

    // ─────────────────────────────── Formats ─────────────────────────────────────

    private static final DecimalFormat PRICE_FORMAT  = new DecimalFormat("#,##0.00");
    private static final DecimalFormat VOLUME_FORMAT = new DecimalFormat("#,##0");

    // Glass-pane materials used for bid / ask / spread / filler slots
    static final Material ASK_MATERIAL    = Material.RED_STAINED_GLASS_PANE;
    static final Material BID_MATERIAL    = Material.LIME_STAINED_GLASS_PANE;
    static final Material SPREAD_MATERIAL = Material.YELLOW_STAINED_GLASS_PANE;
    static final Material FILLER_MATERIAL = Material.GRAY_STAINED_GLASS_PANE;

    // Private constructor — utility class
    private DepthRenderer() {}

    // ─────────────────────────────── Public renderers ─────────────────────────────

    /**
     * Renders an ASK (sell-side) depth level as a red stained-glass pane.
     *
     * @param price       ask price for this level
     * @param totalVolume total quantity available at this price
     * @param rank        1 = best (lowest) ask; higher = worse
     * @param currency    currency symbol (e.g. "$") from config
     * @return the rendered {@link ItemStack}
     */
    public static ItemStack renderAskLevel(double price, int totalVolume, int rank, String currency) {
        ItemStack glass = new ItemStack(ASK_MATERIAL);
        ItemMeta meta = glass.getItemMeta();
        if (meta == null) return glass;

        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "ASK #" + rank
                + "  " + currency + PRICE_FORMAT.format(price));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Price:  " + ChatColor.WHITE + currency + PRICE_FORMAT.format(price));
        lore.add(ChatColor.GRAY + "Volume: " + ChatColor.WHITE + VOLUME_FORMAT.format(totalVolume));
        lore.add(ChatColor.DARK_GRAY + "(sell orders at this price)");
        meta.setLore(lore);
        glass.setItemMeta(meta);
        return glass;
    }

    /**
     * Renders a BID (buy-side) depth level as a lime stained-glass pane.
     *
     * @param price       bid price for this level
     * @param totalVolume total quantity wanted at this price
     * @param rank        1 = best (highest) bid; higher = worse
     * @param currency    currency symbol from config
     * @return the rendered {@link ItemStack}
     */
    public static ItemStack renderBidLevel(double price, int totalVolume, int rank, String currency) {
        ItemStack glass = new ItemStack(BID_MATERIAL);
        ItemMeta meta = glass.getItemMeta();
        if (meta == null) return glass;

        meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "BID #" + rank
                + "  " + currency + PRICE_FORMAT.format(price));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Price:  " + ChatColor.WHITE + currency + PRICE_FORMAT.format(price));
        lore.add(ChatColor.GRAY + "Volume: " + ChatColor.WHITE + VOLUME_FORMAT.format(totalVolume));
        lore.add(ChatColor.DARK_GRAY + "(buy orders at this price)");
        meta.setLore(lore);
        glass.setItemMeta(meta);
        return glass;
    }

    /**
     * Renders the spread indicator slot (yellow glass, slot 14 in {@link MarketGui}).
     *
     * @param spread   difference between best ask and best bid; 0 if book is one-sided
     * @param currency currency symbol from config
     * @return the rendered {@link ItemStack}
     */
    public static ItemStack renderSpread(double spread, String currency) {
        ItemStack glass = new ItemStack(SPREAD_MATERIAL);
        ItemMeta meta = glass.getItemMeta();
        if (meta == null) return glass;

        meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "SPREAD");
        List<String> lore = new ArrayList<>();
        if (spread > 0) {
            lore.add(ChatColor.WHITE + currency + PRICE_FORMAT.format(spread));
            lore.add(ChatColor.GRAY + "Difference between best ask");
            lore.add(ChatColor.GRAY + "and best bid.");
        } else {
            lore.add(ChatColor.GRAY + "No spread data");
            lore.add(ChatColor.DARK_GRAY + "(one side of book is empty)");
        }
        meta.setLore(lore);
        glass.setItemMeta(meta);
        return glass;
    }

    /**
     * Renders an empty / placeholder glass pane for unfilled depth slots.
     *
     * @return a named gray stained-glass pane
     */
    public static ItemStack renderEmpty() {
        ItemStack glass = new ItemStack(FILLER_MATERIAL);
        ItemMeta meta = glass.getItemMeta();
        if (meta == null) return glass;
        meta.setDisplayName(ChatColor.DARK_GRAY + "— No orders —");
        glass.setItemMeta(meta);
        return glass;
    }

    /**
     * Renders an invisible filler pane (no display name, used for layout padding).
     */
    public static ItemStack renderFiller() {
        ItemStack glass = new ItemStack(FILLER_MATERIAL);
        ItemMeta meta = glass.getItemMeta();
        if (meta == null) return glass;
        meta.setDisplayName(" ");
        glass.setItemMeta(meta);
        return glass;
    }

    /**
     * Renders the header asset-icon item shown in slot 4.
     *
     * @param assetMaterial Bukkit material matching the asset (e.g. {@code Material.DIAMOND})
     * @param symbol        asset symbol (e.g. "DIAMOND")
     * @param displayName   human-readable name (e.g. "Diamond")
     * @param snapshot      the current depth snapshot
     * @param currency      currency symbol from config
     * @return the rendered {@link ItemStack}
     */
    public static ItemStack renderAssetIcon(
            Material assetMaterial,
            String symbol,
            String displayName,
            MarketDepthSnapshot snapshot,
            String currency
    ) {
        ItemStack item = new ItemStack(assetMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + displayName
                + ChatColor.GRAY + " (" + symbol + ")");

        List<String> lore = new ArrayList<>();
        double lastPrice = snapshot.lastTradePrice();
        if (lastPrice > 0) {
            lore.add(ChatColor.GRAY + "Last:   " + ChatColor.WHITE
                    + currency + PRICE_FORMAT.format(lastPrice));
        } else {
            lore.add(ChatColor.GRAY + "Last:   " + ChatColor.DARK_GRAY + "No trades yet");
        }

        // Best bid / ask from snapshot
        if (!snapshot.bids().isEmpty()) {
            lore.add(ChatColor.GREEN + "Best Bid: " + currency
                    + PRICE_FORMAT.format(snapshot.bids().get(0).price()));
        }
        if (!snapshot.asks().isEmpty()) {
            lore.add(ChatColor.RED + "Best Ask: " + currency
                    + PRICE_FORMAT.format(snapshot.asks().get(0).price()));
        }

        if (!snapshot.bids().isEmpty() && !snapshot.asks().isEmpty()) {
            lore.add(ChatColor.YELLOW + "Spread: " + currency
                    + PRICE_FORMAT.format(snapshot.spread()));
        }

        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "24h Volume: " + VOLUME_FORMAT.format(snapshot.totalVolume24h()));

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ─────────────────────────────── Utility ──────────────────────────────────────

    /**
     * Formats a price value using the standard price decimal format.
     *
     * @param price raw double price
     * @return formatted string (e.g. "1,234.56")
     */
    public static String formatPrice(double price) {
        return PRICE_FORMAT.format(price);
    }
}
