package com.perseusj.blockstreet.gui;

import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.engine.MatchingEngine;
import com.perseusj.blockstreet.engine.OrderSubmissionService;
import com.perseusj.blockstreet.engine.book.MarketDepthSnapshot;
import com.perseusj.blockstreet.engine.book.OrderBook;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central coordinator for all BlockStreet GUI sessions.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Opening and closing Market / OrderEntry / ActiveOrders GUIs.</li>
 *   <li>Maintaining the {@link #openSessions} registry so event handlers can
 *       identify BlockStreet inventories without fragile title checks.</li>
 *   <li>Running the repeating {@link BukkitRunnable} that refreshes open
 *       {@link MarketGui} instances every {@code gui-refresh-ticks} ticks.</li>
 *   <li>Routing {@link InventoryClickEvent} and {@link InventoryCloseEvent} to
 *       the correct handler based on {@link GuiSession#type()}.</li>
 *   <li>Managing the {@link #pendingInputs} map of active {@link ChatInputCatcher}s
 *       and performing periodic expiry cleanup.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>{@link #openSessions} is a {@link ConcurrentHashMap} — safe for the rare
 *       concurrent access between the Main Thread and async event threads that
 *       only {@code containsKey} / {@code get}.</li>
 *   <li>{@link #pendingInputs} is also a {@link ConcurrentHashMap} — the
 *       {@code AsyncPlayerChatEvent} handler (async thread) reads it while the
 *       Main Thread mutates it.</li>
 *   <li>All Bukkit API calls (inventory, player) occur on the Main Thread only.</li>
 * </ul>
 */
public final class GuiManager {

    // ─────────────────────────── Dependencies ────────────────────────────────────

    private final Plugin                plugin;
    private final MatchingEngine        engine;
    private final OrderSubmissionService orderSubmissionService;
    private final ConfigManager         config;
    private final Logger                logger;

    // ─────────────────────────── Session registries ───────────────────────────────

    /**
     * Maps player UUID → open BlockStreet GUI session.
     * A player can have at most one BlockStreet GUI open at a time.
     * <p>Thread-safe: ConcurrentHashMap.
     */
    private final Map<UUID, GuiSession> openSessions = new ConcurrentHashMap<>();

    /**
     * Maps player UUID → active ChatInputCatcher (price/quantity entry via chat).
     * <p>Thread-safe: ConcurrentHashMap. Read by the async chat handler.
     */
    private final Map<UUID, ChatInputCatcher> pendingInputs = new ConcurrentHashMap<>();

    // ─────────────────────────── Live MarketGui objects ───────────────────────────

    /**
     * Per-symbol live MarketGui instances. Refreshed by the tick loop.
     * Multiple players viewing the same symbol share one inventory copy.
     *
     * <p>Key = asset symbol (e.g. "DIAMOND"); Value = the corresponding MarketGui.
     */
    private final Map<String, MarketGui> marketGuis = new ConcurrentHashMap<>();

    // ─────────────────────────── Refresh task ────────────────────────────────────

    private BukkitTask refreshTask;
    private BukkitTask cleanupTask;

    // ─────────────────────────── Chat input timeout ───────────────────────────────

    private static final long CHAT_INPUT_TIMEOUT_MS = 30_000L; // 30 seconds

    // ─────────────────────────── Constructor ─────────────────────────────────────

    /**
     * Creates the GuiManager. Call {@link #start()} after construction to begin
     * the refresh loop.
     *
     * @param plugin               the plugin instance (for scheduler)
     * @param engine               the matching engine (for book reads and order access)
     * @param orderSubmissionService the submission/cancellation service
     * @param config               config manager (for refresh tick rate, currency symbol)
     */
    public GuiManager(Plugin plugin, MatchingEngine engine,
                      OrderSubmissionService orderSubmissionService,
                      ConfigManager config) {
        this.plugin                = plugin;
        this.engine                = engine;
        this.orderSubmissionService = orderSubmissionService;
        this.config                = config;
        this.logger                = plugin.getLogger();
    }

    // ─────────────────────────── Lifecycle ───────────────────────────────────────

    /**
     * Starts the repeating GUI refresh loop and the expired-catcher cleanup task.
     * Must be called on the Main Thread after the plugin enables.
     */
    public void start() {
        int refreshTicks = config.getGuiRefreshTicks();

        refreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                refreshOpenMarketGuis();
            }
        }.runTaskTimer(plugin, refreshTicks, refreshTicks);

        // Cleanup expired ChatInputCatchers every 5 seconds (100 ticks)
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanupExpiredCatchers();
            }
        }.runTaskTimer(plugin, 100L, 100L);

        logger.info("[GuiManager] Started GUI refresh every " + refreshTicks
                + " tick(s); catcher cleanup every 5 s.");
    }

    /**
     * Cancels the refresh and cleanup tasks. Call from {@code onDisable()}.
     */
    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    // ─────────────────────────── Open GUI entry points ───────────────────────────

    /**
     * Opens the main market GUI for the given player and asset symbol.
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param player the player to open the GUI for
     * @param symbol the asset symbol (e.g. "DIAMOND")
     */
    public void openMarketGui(Player player, String symbol) {
        AssetConfig asset = config.getAsset(symbol);
        if (asset == null || !asset.isEnabled()) {
            player.sendMessage("§c[BlockStreet] Unknown or disabled asset: " + symbol);
            return;
        }

        // Reuse or create a shared MarketGui for this symbol
        MarketGui gui = marketGuis.computeIfAbsent(symbol,
                s -> new MarketGui(asset, config.getCurrencySymbol()));

        // Force an immediate refresh before opening so data is current
        OrderBook book = engine.getBook(symbol);
        if (book != null) {
            gui.refresh(book.getDepthSnapshot());
        }

        Inventory inv = gui.getInventory();
        openSessions.put(player.getUniqueId(),
                new GuiSession(player.getUniqueId(), GuiType.MARKET, inv, symbol));
        player.openInventory(inv);
    }

    /**
     * Opens the order-entry GUI (LIMIT vs MARKET type selection) for the given player.
     * Also stores the order side in {@link #pendingSides} for retrieval during click handling.
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param player player placing the order
     * @param symbol asset symbol
     * @param side   BUY or SELL
     */
    public void openOrderEntryGui(Player player, String symbol, OrderSide side) {
        AssetConfig asset = config.getAsset(symbol);
        if (asset == null || !asset.isEnabled()) {
            player.sendMessage("§c[BlockStreet] Unknown or disabled asset: " + symbol);
            return;
        }
        // Store side so handleOrderEntryClick can retrieve it without parsing the title
        pendingSides.put(player.getUniqueId(), side);

        OrderEntryGui gui = new OrderEntryGui(asset, side, config.getCurrencySymbol());
        Inventory inv     = gui.getInventory();
        openSessions.put(player.getUniqueId(),
                new GuiSession(player.getUniqueId(), GuiType.ORDER_ENTRY, inv, symbol));
        player.openInventory(inv);
    }

    /**
     * Opens the active-orders GUI for the given player and asset symbol.
     * Caches the {@link ActiveOrdersGui} object in {@link #activeOrdersGuiCache} so
     * page-navigation clicks can call nextPage() / prevPage().
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param player player whose orders are displayed
     * @param symbol asset symbol
     */
    public void openActiveOrdersGui(Player player, String symbol) {
        ActiveOrdersGui gui = new ActiveOrdersGui(
                player.getUniqueId(), symbol, engine, config.getCurrencySymbol());
        activeOrdersGuiCache.put(player.getUniqueId(), gui);

        Inventory inv = gui.getInventory();
        openSessions.put(player.getUniqueId(),
                new GuiSession(player.getUniqueId(), GuiType.ACTIVE_ORDERS, inv, symbol));
        player.openInventory(inv);
    }

    // ─────────────────────────── Session registry (for listener access) ───────────

    /**
     * Returns the open {@link GuiSession} for the given player, or {@code null} if the
     * player does not have a BlockStreet GUI open.
     *
     * <p>Thread-safe — may be called from the async event thread for a quick
     * containsKey-style check, but any Bukkit API work must be done on the Main Thread.
     */
    public GuiSession getSession(UUID playerId) {
        return openSessions.get(playerId);
    }

    /**
     * Returns {@code true} if the given {@link Inventory} object belongs to any
     * open BlockStreet GUI session. Used by the click/close event listener to
     * ignore non-BlockStreet inventories quickly.
     */
    public boolean isBlockStreetInventory(Inventory inv) {
        for (GuiSession session : openSessions.values()) {
            if (session.inventory().equals(inv)) return true;
        }
        return false;
    }

    /**
     * Removes the session for the given player.
     * Called when the player closes the GUI or the GUI is replaced by another.
     */
    public void closeSession(UUID playerId) {
        openSessions.remove(playerId);
    }

    // ─────────────────────────── Pending input registry ──────────────────────────

    /**
     * Registers a {@link ChatInputCatcher} for a player who is about to enter
     * price/quantity values via chat.
     *
     * @param catcher the catcher to register
     */
    public void registerChatCatcher(ChatInputCatcher catcher) {
        pendingInputs.put(catcher.playerId, catcher);
    }

    /**
     * Returns the active {@link ChatInputCatcher} for the given player, or
     * {@code null} if there is none.
     *
     * <p>Thread-safe — safe to call from async chat thread.
     */
    public ChatInputCatcher getChatCatcher(UUID playerId) {
        return pendingInputs.get(playerId);
    }

    /**
     * Removes and returns the active {@link ChatInputCatcher} for the given player.
     * The caller is responsible for acting on the collected data (building an order).
     *
     * <p>Thread-safe — safe to call from async chat thread (remove is atomic on ConcurrentHashMap).
     */
    public ChatInputCatcher removeChatCatcher(UUID playerId) {
        return pendingInputs.remove(playerId);
    }

    // ─────────────────────────── Event routing ────────────────────────────────────

    /**
     * Routes an {@link InventoryClickEvent} that belongs to a BlockStreet GUI.
     * All click handling cancels the event by default to prevent item theft.
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param event   the click event (already confirmed to be a BlockStreet GUI)
     * @param session the session for the clicking player
     */
    public void handleClick(InventoryClickEvent event, GuiSession session) {
        // Always cancel — no taking items from any BlockStreet GUI
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot   = event.getRawSlot();
        String sym = session.symbol();

        switch (session.type()) {
            case MARKET         -> handleMarketClick(player, slot, sym);
            case ORDER_ENTRY    -> handleOrderEntryClick(player, slot, sym);
            case ACTIVE_ORDERS  -> handleActiveOrdersClick(player, slot, sym, event.getInventory());
        }
    }

    /**
     * Routes an {@link InventoryCloseEvent} for a BlockStreet GUI.
     *
     * <p><strong>Main Thread only.</strong>
     */
    public void handleClose(InventoryCloseEvent event, GuiSession session) {
        closeSession(session.playerId());
    }

    // ─────────────────────────── MarketGui click handling ────────────────────────

    private void handleMarketClick(Player player, int slot, String symbol) {
        switch (slot) {
            case MarketGui.SLOT_BUY_ORDER  -> openOrderEntryGui(player, symbol, OrderSide.BUY);
            case MarketGui.SLOT_SELL_ORDER -> openOrderEntryGui(player, symbol, OrderSide.SELL);
            case MarketGui.SLOT_CANCEL,
                 MarketGui.SLOT_MY_ORDERS  -> openActiveOrdersGui(player, symbol);
            case MarketGui.SLOT_BACK       -> player.closeInventory();
            // All other slots: no action (depth levels are display-only)
        }
    }

    // ─────────────────────────── OrderEntryGui click handling ─────────────────────

    private void handleOrderEntryClick(Player player, int slot, String symbol) {
        // Retrieve the order side stored when openOrderEntryGui was called
        OrderSide side = pendingSides.getOrDefault(player.getUniqueId(), OrderSide.BUY);

        switch (slot) {
            case OrderEntryGui.SLOT_LIMIT  -> startChatInput(player, symbol, side, OrderType.LIMIT);
            case OrderEntryGui.SLOT_MARKET -> startChatInput(player, symbol, side, OrderType.MARKET);
            case OrderEntryGui.SLOT_BACK   -> openMarketGui(player, symbol);
        }
    }

    // ─────────────────────────── ActiveOrdersGui click handling ───────────────────

    private void handleActiveOrdersClick(Player player, int slot, String symbol, Inventory inv) {
        // Retrieve the ActiveOrdersGui — we need a reference to it.
        // We use the activeOrdersGuiCache map to avoid reconstructing it.
        ActiveOrdersGui gui = activeOrdersGuiCache.get(player.getUniqueId());
        if (gui == null) return;

        switch (slot) {
            case ActiveOrdersGui.SLOT_PREV -> {
                gui.prevPage();
                player.updateInventory();
            }
            case ActiveOrdersGui.SLOT_NEXT -> {
                gui.nextPage();
                player.updateInventory();
            }
            case ActiveOrdersGui.SLOT_BACK -> openMarketGui(player, symbol);
            default -> {
                // Slots 0–44 are order items
                if (slot >= 0 && slot < 45) {
                    Order order = gui.getOrderAt(slot);
                    if (order != null) {
                        boolean cancelled = orderSubmissionService.cancelOrder(order);
                        if (cancelled) {
                            player.sendMessage(ChatColor.YELLOW
                                    + "[BlockStreet] Cancelling order "
                                    + order.getOrderId().toString().substring(0, 8) + "…");
                            gui.refresh();
                            player.updateInventory();
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────── Chat input flow ──────────────────────────────────

    /**
     * Closes the current GUI and starts the chat-input collection flow.
     * Registers a {@link ChatInputCatcher}, then sends the first prompt.
     *
     * <p><strong>Main Thread only.</strong>
     */
    public void startChatInput(Player player, String symbol, OrderSide side, OrderType type) {
        ChatInputCatcher catcher = new ChatInputCatcher(
                player.getUniqueId(), symbol, side, type, CHAT_INPUT_TIMEOUT_MS);
        registerChatCatcher(catcher);

        // Close the current GUI cleanly (fires InventoryCloseEvent, handled above)
        player.closeInventory();

        // Send the first prompt
        if (type == OrderType.LIMIT) {
            player.sendMessage(ChatColor.GOLD + "[BlockStreet] "
                    + ChatColor.WHITE + "Enter your LIMIT PRICE for "
                    + symbol + " (or type " + ChatColor.RED + "cancel" + ChatColor.WHITE + "):");
        } else {
            player.sendMessage(ChatColor.GOLD + "[BlockStreet] "
                    + ChatColor.WHITE + "Enter the QUANTITY you want to "
                    + (side == OrderSide.BUY ? "BUY" : "SELL")
                    + " (or type " + ChatColor.RED + "cancel" + ChatColor.WHITE + "):");
        }
    }

    /**
     * Called by the async chat event handler (via a {@code runTask} callback on the Main Thread)
     * after both price and quantity have been collected.
     *
     * <p>Completes Phase 2A (asset lock) and submits the order to the engine.
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param catcher the completed catcher with {@link ChatInputCatcher#tempPrice} and
     *                {@link ChatInputCatcher#tempQty} populated
     */
    public void completeChatInput(ChatInputCatcher catcher) {
        Player player = Bukkit.getPlayer(catcher.playerId);
        if (player == null || !player.isOnline()) {
            logger.fine("[GuiManager] Player disconnected before order completion: " + catcher.playerId);
            return;
        }

        // Delegate to OrderSubmissionService (Phase 2A lock + engine submit)
        orderSubmissionService.submitOrder(
                player,
                catcher.symbol,
                catcher.side,
                catcher.orderType,
                catcher.tempPrice,   // 0.0 for MARKET
                catcher.tempQty
        );

        // Return player to the market GUI
        openMarketGui(player, catcher.symbol);
    }

    // ─────────────────────────── Supplementary state maps ────────────────────────

    /**
     * Tracks the {@link OrderSide} for a player who has an {@link OrderEntryGui} open,
     * because GuiSession doesn't carry side information.
     * Main Thread only for writes; Main Thread for reads (in handleOrderEntryClick).
     */
    private final Map<UUID, OrderSide> pendingSides = new ConcurrentHashMap<>();

    /**
     * Tracks live {@link ActiveOrdersGui} objects so page-navigation clicks can call
     * {@link ActiveOrdersGui#nextPage()} / {@link ActiveOrdersGui#prevPage()}.
     * Main Thread only for writes; Main Thread for reads (in handleActiveOrdersClick).
     */
    private final Map<UUID, ActiveOrdersGui> activeOrdersGuiCache = new ConcurrentHashMap<>();

    @Override
    public String toString() {
        return "GuiManager{sessions=" + openSessions.size() + ", pending=" + pendingInputs.size() + "}";
    }

    // ─────────────────────────── Refresh loop ─────────────────────────────────────

    /**
     * Refreshes all open {@link MarketGui} inventories with the latest depth data.
     * Runs on the Main Thread via the repeating {@link BukkitRunnable}.
     */
    private void refreshOpenMarketGuis() {
        for (Map.Entry<UUID, GuiSession> entry : openSessions.entrySet()) {
            GuiSession session = entry.getValue();
            if (session.type() != GuiType.MARKET) continue;

            MarketGui gui = marketGuis.get(session.symbol());
            if (gui == null) continue;

            OrderBook book = engine.getBook(session.symbol());
            if (book == null) continue;

            MarketDepthSnapshot snapshot = book.getDepthSnapshot();
            gui.refresh(snapshot);

            // Notify the player's client that the inventory contents changed
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.updateInventory();
            }
        }
    }

    // ─────────────────────────── Catcher expiry ───────────────────────────────────

    /**
     * Removes expired {@link ChatInputCatcher}s and notifies the players.
     * Runs on the Main Thread via the repeating BukkitRunnable every 100 ticks.
     */
    private void cleanupExpiredCatchers() {
        pendingInputs.entrySet().removeIf(entry -> {
            ChatInputCatcher catcher = entry.getValue();
            if (catcher.isExpired()) {
                Player player = Bukkit.getPlayer(catcher.playerId);
                if (player != null && player.isOnline()) {
                    player.sendMessage(ChatColor.YELLOW
                            + "[BlockStreet] Order entry timed out. Returning to market.");
                    openMarketGui(player, catcher.symbol);
                }
                return true; // remove from map
            }
            return false;
        });
    }

    // ─────────────────────────── Player quit cleanup ──────────────────────────────

    /**
     * Cleans up all state for a player who has disconnected.
     *
     * <p>Should be called from {@code PlayerQuitEvent} in the listener.
     * Removes the open session, any pending chat catcher, and supplementary maps.
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param playerId the disconnecting player's UUID
     */
    public void handlePlayerQuit(UUID playerId) {
        openSessions.remove(playerId);
        pendingInputs.remove(playerId);
        pendingSides.remove(playerId);
        activeOrdersGuiCache.remove(playerId);
    }

}

