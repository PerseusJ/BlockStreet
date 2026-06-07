package com.perseusj.blockstreet.gui;

import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * A compact 3-row (27-slot) order-entry screen where the player chooses
 * order type (LIMIT vs MARKET) before the price/quantity chat-input flow starts.
 *
 * <h2>Layout</h2>
 * <pre>
 * Row 0 [0–8 ]: Header + asset icon (slot 4)
 * Row 1 [9–17]: Order type buttons — LIMIT (slot 11), MARKET (slot 15)
 * Row 2[18–26]: Confirm / Back navigation
 * </pre>
 *
 * <p>After the player clicks LIMIT or MARKET, the GUI is closed and a
 * {@link ChatInputCatcher} is registered to collect the price (for LIMIT only)
 * and quantity from chat.
 *
 * <h2>Thread Safety</h2>
 * All methods must be called from the <strong>Server Main Thread</strong>.
 */
public final class OrderEntryGui {

    // ─────────────────────────── Slot Constants ───────────────────────────────────

    public static final int SLOT_ASSET_ICON = 4;
    public static final int SLOT_LIMIT      = 11;
    public static final int SLOT_MARKET     = 15;
    public static final int SLOT_BACK       = 18;

    // ─────────────────────────── Fields ──────────────────────────────────────────

    private final Inventory inventory;
    private final AssetConfig assetConfig;
    private final OrderSide   side;

    // ─────────────────────────── Constructor ─────────────────────────────────────

    /**
     * Creates a new order-entry GUI.
     *
     * @param assetConfig  the asset being traded
     * @param side         {@link OrderSide#BUY} or {@link OrderSide#SELL}
     * @param currencySymbol currency symbol from config (e.g. "$")
     */
    public OrderEntryGui(AssetConfig assetConfig, OrderSide side, String currencySymbol) {
        this.assetConfig = assetConfig;
        this.side        = side;

        String sideLabel = (side == OrderSide.BUY) ? "BUY" : "SELL";
        String color     = (side == OrderSide.BUY)
                ? ChatColor.GREEN.toString() : ChatColor.RED.toString();

        String title = ChatColor.DARK_GRAY + "❙ " + color + ChatColor.BOLD
                + sideLabel + " " + assetConfig.getDisplayName();
        this.inventory = Bukkit.createInventory(null, 27, title);

        paintLayout(currencySymbol);
    }

    // ─────────────────────────── Layout ──────────────────────────────────────────

    private void paintLayout(String currencySymbol) {
        ItemStack filler = DepthRenderer.renderFiller();
        for (int i = 0; i < 27; i++) inventory.setItem(i, filler);

        // Asset icon (slot 4)
        inventory.setItem(SLOT_ASSET_ICON, buildAssetIcon(currencySymbol));

        // Order type buttons
        inventory.setItem(SLOT_LIMIT,  buildLimitButton());
        inventory.setItem(SLOT_MARKET, buildMarketButton());

        // Back button (bottom-left)
        inventory.setItem(SLOT_BACK, buildBackButton());
    }

    // ─────────────────────────── Inventory accessor ───────────────────────────────

    /** Returns the underlying Bukkit inventory. */
    public Inventory getInventory() { return inventory; }

    /** Returns the order side this entry GUI is configured for. */
    public OrderSide getSide() { return side; }

    // ─────────────────────────── Button builders ──────────────────────────────────

    private ItemStack buildAssetIcon(String currency) {
        ItemStack item = new ItemStack(assetConfig.getMaterial());
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            String sideColor = (side == OrderSide.BUY)
                    ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
            String sideLabel = (side == OrderSide.BUY) ? "BUY" : "SELL";
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + assetConfig.getDisplayName());
            List<String> lore = new ArrayList<>();
            lore.add(sideColor + ChatColor.BOLD + "Placing: " + sideLabel + " order");
            lore.add(ChatColor.GRAY + "Select order type below.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildLimitButton() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "LIMIT Order");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Specify exact price & quantity.");
            lore.add(ChatColor.GRAY + "Rests in book until filled.");
            lore.add("");
            lore.add(ChatColor.WHITE + "Click to continue.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildMarketButton() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "MARKET Order");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Specify quantity only.");
            lore.add(ChatColor.GRAY + "Fills immediately at best price.");
            lore.add("");
            lore.add(ChatColor.WHITE + "Click to continue.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildBackButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + "← Back");
            item.setItemMeta(meta);
        }
        return item;
    }
}
