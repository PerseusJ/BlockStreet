package com.perseusj.blockstreet.gui;

import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.engine.book.MarketDepthSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The main 54-slot (6-row) market trading screen for a single asset.
 *
 * <h2>Slot Layout</h2>
 * <pre>
 * Row 0  [0–8 ]: Header banner + asset icon (slot 4) + filler
 * Row 1  [9–17]: Ask levels — slots 9–13 (rank 5→1, worst→best ask)
 * Row 2 [18–26]: Ask levels continued — slots 18–19 (slots for overflow);
 *                Spread indicator — slot 14;
 *                Bid levels start — slots 15–19 (best→worst bid, rank 1→3)
 * Row 3 [27–35]: Bid levels — slots 27–35 (bid rank 4–5 in 27–28)
 * Row 4 [36–44]: Action buttons — BUY (36), CANCEL (40), SELL (44)
 * Row 5 [45–53]: Stats bar — BACK (45), MY ORDERS (49), filler
 * </pre>
 *
 * <p>The exact slot assignments from the plan:
 * <ul>
 *   <li>Slot 4  — Asset display item (header icon)</li>
 *   <li>Slots 9–13 — Ask levels rank 5→1 (worst→best, red glass)</li>
 *   <li>Slot 14 — Spread indicator (yellow glass)</li>
 *   <li>Slots 15–19 — Bid levels rank 1→5 (best→worst, lime glass)</li>
 *   <li>Slot 36 — [PLACE BUY ORDER] — Emerald Block</li>
 *   <li>Slot 40 — [CANCEL ORDER] — Barrier</li>
 *   <li>Slot 44 — [PLACE SELL ORDER] — Redstone Block</li>
 *   <li>Slot 45 — Back / close</li>
 *   <li>Slot 49 — [MY ORDERS] — Paper</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * All methods must be called from the <strong>Server Main Thread</strong>.
 * The GUI reads {@code OrderBook.getDepthSnapshot()} which is a volatile reference —
 * safe to read from the Main Thread without any additional synchronisation.
 */
public final class MarketGui {

    // ─────────────────────────── Slot Constants ────────────────────────────────────

    /** Slot containing the asset header icon. */
    public static final int SLOT_ASSET_ICON = 4;

    /** Ask depth level slots (worst→best ask = rank 5→1). */
    private static final int[] ASK_SLOTS = {9, 10, 11, 12, 13};

    /** Spread indicator slot. */
    public static final int SLOT_SPREAD = 14;

    /** Bid depth level slots (best→worst bid = rank 1→5). */
    private static final int[] BID_SLOTS = {15, 16, 17, 18, 19};

    /** Action button — place a limit BUY order. */
    public static final int SLOT_BUY_ORDER  = 36;

    /** Action button — cancel a resting order. */
    public static final int SLOT_CANCEL     = 40;

    /** Action button — place a limit SELL order. */
    public static final int SLOT_SELL_ORDER = 44;

    /** Navigation — back / close (bottom-left). */
    public static final int SLOT_BACK       = 45;

    /** Navigation — view my open orders. */
    public static final int SLOT_MY_ORDERS  = 49;

    // ─────────────────────────── Inventory ────────────────────────────────────────

    private final Inventory   inventory;
    private final AssetConfig assetConfig;
    private final String      currencySymbol;

    // ─────────────────────────── Constructor ──────────────────────────────────────

    /**
     * Creates (but does not open) a new MarketGui inventory for the given asset.
     *
     * @param assetConfig    the asset being displayed
     * @param currencySymbol currency symbol from config (e.g. "$")
     */
    public MarketGui(AssetConfig assetConfig, String currencySymbol) {
        this.assetConfig    = assetConfig;
        this.currencySymbol = currencySymbol;

        String title = ChatColor.DARK_GRAY + "❙ " + ChatColor.GOLD + ChatColor.BOLD
                + assetConfig.getDisplayName()
                + ChatColor.DARK_GRAY + " Market";
        this.inventory = Bukkit.createInventory(null, 54, title);

        paintStaticLayout();
    }

    // ─────────────────────────── Static layout ────────────────────────────────────

    /**
     * Paints all static (non-data) slots — fillers, action buttons, and nav buttons.
     * Called once on construction; data slots are filled by {@link #refresh}.
     */
    private void paintStaticLayout() {
        ItemStack filler = DepthRenderer.renderFiller();

        // Fill all 54 slots with filler first
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // ── Row 4: Action buttons ──────────────────────────────────────────────
        inventory.setItem(SLOT_BUY_ORDER,  buildBuyButton());
        inventory.setItem(SLOT_SELL_ORDER, buildSellButton());
        inventory.setItem(SLOT_CANCEL,     buildCancelButton());

        // ── Row 5: Navigation ──────────────────────────────────────────────────
        inventory.setItem(SLOT_BACK,      buildBackButton());
        inventory.setItem(SLOT_MY_ORDERS, buildMyOrdersButton());
    }

    // ─────────────────────────── Dynamic refresh ──────────────────────────────────

    /**
     * Refreshes all data-driven slots with the latest depth snapshot.
     *
     * <p>Called by {@link GuiManager}'s repeating {@code BukkitRunnable} every
     * {@code gui-refresh-ticks} ticks on the Main Thread.
     *
     * @param snapshot the latest depth snapshot from {@code OrderBook.getDepthSnapshot()}
     */
    public void refresh(MarketDepthSnapshot snapshot) {
        // ── Asset icon (slot 4) ────────────────────────────────────────────────
        inventory.setItem(SLOT_ASSET_ICON, DepthRenderer.renderAssetIcon(
                assetConfig.getMaterial(),
                assetConfig.getSymbol(),
                assetConfig.getDisplayName(),
                snapshot,
                currencySymbol
        ));

        // ── Ask slots (9–13): render asks worst→best (rank 5 in slot 9, rank 1 in slot 13) ──
        List<MarketDepthSnapshot.DepthLevel> asks = snapshot.asks();
        for (int i = 0; i < ASK_SLOTS.length; i++) {
            int slot = ASK_SLOTS[i];
            // Slot index 0 = rank 5 (worst ask), slot index 4 = rank 1 (best ask)
            int rankFromBest = ASK_SLOTS.length - i; // 5, 4, 3, 2, 1
            int askIdx = asks.size() - rankFromBest; // negative = no data at this rank
            if (askIdx >= 0 && askIdx < asks.size()) {
                MarketDepthSnapshot.DepthLevel level = asks.get(askIdx);
                inventory.setItem(slot, DepthRenderer.renderAskLevel(
                        level.price(), level.totalVolume(), rankFromBest, currencySymbol));
            } else {
                inventory.setItem(slot, DepthRenderer.renderEmpty());
            }
        }

        // ── Spread (slot 14) ───────────────────────────────────────────────────
        inventory.setItem(SLOT_SPREAD, DepthRenderer.renderSpread(snapshot.spread(), currencySymbol));

        // ── Bid slots (15–19): render bids best→worst (rank 1 in slot 15, rank 5 in slot 19) ─
        List<MarketDepthSnapshot.DepthLevel> bids = snapshot.bids();
        for (int i = 0; i < BID_SLOTS.length; i++) {
            int slot     = BID_SLOTS[i];
            int rankFromBest = i + 1; // 1, 2, 3, 4, 5
            if (i < bids.size()) {
                MarketDepthSnapshot.DepthLevel level = bids.get(i);
                inventory.setItem(slot, DepthRenderer.renderBidLevel(
                        level.price(), level.totalVolume(), rankFromBest, currencySymbol));
            } else {
                inventory.setItem(slot, DepthRenderer.renderEmpty());
            }
        }
    }

    // ─────────────────────────── Inventory accessor ───────────────────────────────

    /**
     * Returns the underlying Bukkit {@link Inventory} for this GUI.
     * Used by {@link GuiManager} to open it for the player.
     */
    public Inventory getInventory() {
        return inventory;
    }

    // ─────────────────────────── Button builders ──────────────────────────────────

    private ItemStack buildBuyButton() {
        ItemStack item = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "✦ PLACE BUY ORDER");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Click to place a limit or");
            lore.add(ChatColor.GRAY + "market BUY order.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildSellButton() {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "✦ PLACE SELL ORDER");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Click to place a limit or");
            lore.add(ChatColor.GRAY + "market SELL order.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildCancelButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "✗ CANCEL ORDER");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "View and cancel your");
            lore.add(ChatColor.GRAY + "resting orders.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildBackButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + "← Close");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildMyOrdersButton() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "📋 MY ORDERS");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "View all your open orders");
            lore.add(ChatColor.GRAY + "for this asset.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
