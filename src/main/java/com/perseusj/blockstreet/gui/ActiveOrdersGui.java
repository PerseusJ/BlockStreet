package com.perseusj.blockstreet.gui;

import com.perseusj.blockstreet.engine.MatchingEngine;
import com.perseusj.blockstreet.engine.book.OrderBook;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderStatus;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A paginated 54-slot GUI that lists a player's currently open (resting) orders
 * for a specific asset symbol.
 *
 * <h2>Layout</h2>
 * <pre>
 * Rows 0–4 [0–44]: Order slots (5 rows × 9 = 45 item slots)
 * Row  5   [45–53]: Navigation — PREV PAGE (slot 45), info (slot 49), NEXT PAGE (slot 53)
 *                   BACK button (slot 47)
 * </pre>
 *
 * <h2>Order Item</h2>
 * Each resting order is rendered as its asset item with lore showing:
 * side, type, price, quantity remaining, and order ID prefix (for cancellation reference).
 *
 * <h2>Thread Safety</h2>
 * All methods must be called from the <strong>Server Main Thread</strong>.
 */
public final class ActiveOrdersGui {

    // ─────────────────────────── Constants ────────────────────────────────────────

    /** Number of order item slots per page. */
    private static final int ORDERS_PER_PAGE = 45;

    // Nav slots in row 5
    public static final int SLOT_PREV = 45;
    public static final int SLOT_BACK = 47;
    public static final int SLOT_INFO = 49;
    public static final int SLOT_NEXT = 53;

    private static final DecimalFormat PRICE_FMT = new DecimalFormat("#,##0.00");

    // ─────────────────────────── Fields ──────────────────────────────────────────

    private final Inventory     inventory;
    private final UUID          playerId;
    private final String        symbol;
    private final MatchingEngine engine;
    private final String        currencySymbol;

    /** Currently rendered page (0-indexed). */
    private int currentPage = 0;

    /** Orders snapshot shown on the current page (set during refresh). */
    private List<Order> pageOrders = new ArrayList<>();

    // ─────────────────────────── Constructor ─────────────────────────────────────

    /**
     * Creates and populates the active-orders GUI for the given player and asset.
     *
     * @param playerId       UUID of the player
     * @param symbol         asset symbol to show orders for
     * @param engine         the matching engine (used to read the order book)
     * @param currencySymbol currency symbol from config
     */
    public ActiveOrdersGui(UUID playerId, String symbol,
                           MatchingEngine engine, String currencySymbol) {
        this.playerId       = playerId;
        this.symbol         = symbol;
        this.engine         = engine;
        this.currencySymbol = currencySymbol;

        String title = ChatColor.DARK_GRAY + "❙ " + ChatColor.AQUA + ChatColor.BOLD
                + "My Orders" + ChatColor.DARK_GRAY + " — " + symbol;
        this.inventory = Bukkit.createInventory(null, 54, title);

        refresh();
    }

    // ─────────────────────────── Refresh ──────────────────────────────────────────

    /**
     * Rebuilds the inventory contents from the current order book state.
     * Call when navigating pages or when the GUI is opened.
     *
     * <p><strong>Main Thread only.</strong>
     */
    public void refresh() {
        // Read all orders for this player from the book (snapshot read — safe on Main Thread)
        OrderBook book = engine.getBook(symbol);
        List<Order> allOrders = (book == null)
                ? new ArrayList<>()
                : book.getAllOrders().stream()
                        .filter(o -> o.getPlayerId().equals(playerId)
                                && (o.getStatus() == OrderStatus.OPEN
                                    || o.getStatus() == OrderStatus.PARTIALLY_FILLED))
                        .collect(Collectors.toList());

        // Calculate page bounds
        int totalPages = Math.max(1, (int) Math.ceil((double) allOrders.size() / ORDERS_PER_PAGE));
        currentPage    = Math.min(currentPage, totalPages - 1);
        int start      = currentPage * ORDERS_PER_PAGE;
        int end        = Math.min(start + ORDERS_PER_PAGE, allOrders.size());
        pageOrders     = allOrders.subList(start, end);

        // Fill all slots with filler first
        ItemStack filler = DepthRenderer.renderFiller();
        for (int i = 0; i < 54; i++) inventory.setItem(i, filler);

        // Render order items
        for (int i = 0; i < pageOrders.size(); i++) {
            inventory.setItem(i, renderOrder(pageOrders.get(i)));
        }

        // Nav bar
        paintNavBar(totalPages);
    }

    // ─────────────────────────── Navigation ───────────────────────────────────────

    /**
     * Advances to the next page and refreshes.
     * Called by {@link GuiManager} when the player clicks the NEXT slot.
     */
    public void nextPage() {
        OrderBook book = engine.getBook(symbol);
        int total = (book == null) ? 0 : (int) book.getAllOrders().stream()
                .filter(o -> o.getPlayerId().equals(playerId)
                        && (o.getStatus() == OrderStatus.OPEN
                            || o.getStatus() == OrderStatus.PARTIALLY_FILLED))
                .count();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / ORDERS_PER_PAGE));
        if (currentPage < totalPages - 1) {
            currentPage++;
            refresh();
        }
    }

    /**
     * Goes back one page and refreshes.
     * Called by {@link GuiManager} when the player clicks the PREV slot.
     */
    public void prevPage() {
        if (currentPage > 0) {
            currentPage--;
            refresh();
        }
    }

    // ─────────────────────────── Order retrieval ──────────────────────────────────

    /**
     * Returns the {@link Order} associated with the given slot index on the current page,
     * or {@code null} if the slot does not correspond to an order.
     *
     * @param slot raw inventory slot (0–44)
     * @return the order, or {@code null}
     */
    public Order getOrderAt(int slot) {
        if (slot < 0 || slot >= pageOrders.size()) return null;
        return pageOrders.get(slot);
    }

    // ─────────────────────────── Inventory accessor ───────────────────────────────

    /** Returns the underlying Bukkit inventory. */
    public Inventory getInventory() { return inventory; }

    // ─────────────────────────── Rendering ───────────────────────────────────────

    private ItemStack renderOrder(Order order) {
        Material mat = order.getSide() == OrderSide.BUY
                ? Material.LIME_DYE : Material.RED_DYE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        if (meta == null) return item;

        String sideColor = order.getSide() == OrderSide.BUY
                ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
        String sideLabel = order.getSide() == OrderSide.BUY ? "BUY" : "SELL";

        meta.setDisplayName(sideColor + ChatColor.BOLD + sideLabel
                + ChatColor.RESET + ChatColor.WHITE + " " + order.getSymbol());

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Type:      " + ChatColor.WHITE + order.getType().name());
        if (order.getLimitPrice() > 0) {
            lore.add(ChatColor.GRAY + "Price:     " + ChatColor.WHITE
                    + currencySymbol + PRICE_FMT.format(order.getLimitPrice()));
        }
        lore.add(ChatColor.GRAY + "Qty Rem:   " + ChatColor.WHITE + order.getQuantityRemaining()
                + ChatColor.DARK_GRAY + " / " + order.getQuantityOriginal());
        lore.add(ChatColor.GRAY + "Status:    " + ChatColor.YELLOW + order.getStatus().name());
        lore.add(ChatColor.DARK_GRAY + "ID: " + order.getOrderId().toString().substring(0, 8) + "…");
        lore.add("");
        lore.add(ChatColor.RED + "Left-click to CANCEL this order");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void paintNavBar(int totalPages) {
        inventory.setItem(SLOT_PREV, buildPrevButton());
        inventory.setItem(SLOT_NEXT, buildNextButton());
        inventory.setItem(SLOT_BACK, buildBackButton());
        inventory.setItem(SLOT_INFO, buildPageInfo(currentPage, totalPages));
    }

    private ItemStack buildPrevButton() {
        ItemStack item = new ItemStack(currentPage > 0 ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(currentPage > 0
                    ? ChatColor.WHITE + "← Previous Page"
                    : ChatColor.DARK_GRAY + "← (First Page)");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildNextButton() {
        // We recompute total quickly just for the visual state
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + "Next Page →");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildBackButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "← Back to Market");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildPageInfo(int page, int total) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "My Open Orders");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Page " + (page + 1) + " of " + total);
            lore.add(ChatColor.DARK_GRAY + "Left-click an order to cancel it.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
