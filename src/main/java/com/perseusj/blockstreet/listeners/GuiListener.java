package com.perseusj.blockstreet.listeners;

import com.perseusj.blockstreet.gui.ChatInputCatcher;
import com.perseusj.blockstreet.gui.GuiManager;
import com.perseusj.blockstreet.gui.GuiSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Handles all Bukkit events required by the BlockStreet GUI layer (Phase 3).
 *
 * <h2>Events handled</h2>
 * <ul>
 *   <li>{@link InventoryClickEvent} — routes clicks in BlockStreet GUIs to
 *       {@link GuiManager#handleClick}. Cancels all clicks in BlockStreet
 *       inventories to prevent item theft.</li>
 *   <li>{@link InventoryCloseEvent} — removes the session from
 *       {@link GuiManager#closeSession} when the player closes a BlockStreet
 *       GUI.</li>
 *   <li>{@link AsyncPlayerChatEvent} — intercepts chat messages from players
 *       who are in an active {@link ChatInputCatcher} flow (price/qty entry).
 *       The event is cancelled (so the message is not broadcast) and the input
 *       is parsed and handed back to the Main Thread via
 *       {@code Bukkit.getScheduler().runTask()}.</li>
 *   <li>{@link PlayerQuitEvent} — cleans up all GUI state for the disconnecting
 *       player via {@link GuiManager#handlePlayerQuit}.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * {@link AsyncPlayerChatEvent} fires on an async thread. This handler
 * <strong>only</strong> performs:
 * <ol>
 *   <li>A {@code ConcurrentHashMap.get()} to find the catcher (thread-safe).</li>
 *   <li>Simple string parsing (no Bukkit API).</li>
 *   <li>A {@code Bukkit.getScheduler().runTask()} to hand off to the Main Thread.</li>
 * </ol>
 * All actual Bukkit API calls happen on the Main Thread inside the lambda.
 */
public final class GuiListener implements Listener {

    private final GuiManager guiManager;
    private final Plugin      plugin;

    public GuiListener(GuiManager guiManager, Plugin plugin) {
        this.guiManager = guiManager;
        this.plugin     = plugin;
    }

    // ─────────────────────────── Inventory events ─────────────────────────────────

    /**
     * Intercepts all inventory clicks. If the clicked inventory belongs to a
     * BlockStreet GUI session, the event is cancelled and routed to
     * {@link GuiManager#handleClick}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        GuiSession session = guiManager.getSession(player.getUniqueId());
        if (session == null) return;
        if (!session.inventory().equals(event.getInventory())) return;

        // Route to GuiManager (also cancels the event internally)
        guiManager.handleClick(event, session);
    }

    /**
     * Removes the session when the player closes any BlockStreet GUI.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        GuiSession session = guiManager.getSession(player.getUniqueId());
        if (session == null) return;
        if (!session.inventory().equals(event.getInventory())) return;

        guiManager.handleClose(event, session);
    }

    // ─────────────────────────── Chat input capture ───────────────────────────────

    /**
     * Intercepts chat messages for players in an active {@link ChatInputCatcher}
     * flow. The event priority is HIGHEST so we process before other plugins,
     * and the event is marked cancelled so the message is not broadcast.
     *
     * <p><strong>Async thread — no Bukkit API calls here except runTask.</strong>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player   = event.getPlayer();
        ChatInputCatcher catcher = guiManager.getChatCatcher(player.getUniqueId());
        if (catcher == null) return;

        // This player is in an order-entry flow — suppress the chat message
        event.setCancelled(true);

        String message = event.getMessage().trim();

        // ── Cancel word ────────────────────────────────────────────────────────
        if (message.equalsIgnoreCase("cancel")) {
            guiManager.removeChatCatcher(player.getUniqueId());
            // Hand off to Main Thread to reopen market GUI
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player onlinePlayer = plugin.getServer().getPlayer(player.getUniqueId());
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    onlinePlayer.sendMessage("§e[BlockStreet] §fOrder entry cancelled.");
                    guiManager.openMarketGui(onlinePlayer, catcher.symbol);
                }
            });
            return;
        }

        // ── PRICE step ─────────────────────────────────────────────────────────
        if (catcher.isAwaitingPrice()) {
            double price;
            try {
                price = Double.parseDouble(message);
            } catch (NumberFormatException e) {
                // Notify on Main Thread (sendMessage is safe to call from async, but
                // we stay consistent and use runTask for all player messaging)
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player p = plugin.getServer().getPlayer(player.getUniqueId());
                    if (p != null) {
                        p.sendMessage("§c[BlockStreet] Invalid price. Please enter a number, or type §4cancel§c.");
                    }
                });
                return;
            }

            if (price <= 0) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player p = plugin.getServer().getPlayer(player.getUniqueId());
                    if (p != null) {
                        p.sendMessage("§c[BlockStreet] Price must be greater than zero.");
                    }
                });
                return;
            }

            catcher.recordPrice(price);

            // Prompt for quantity (Main Thread for consistent messaging)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player p = plugin.getServer().getPlayer(player.getUniqueId());
                if (p != null) {
                    p.sendMessage("§6[BlockStreet] §fPrice set to §a" + price
                            + "§f. Now enter QUANTITY (or type §c cancel§f):");
                }
            });
            return;
        }

        // ── QUANTITY step ──────────────────────────────────────────────────────
        if (catcher.isAwaitingQuantity()) {
            int qty;
            try {
                qty = Integer.parseInt(message);
            } catch (NumberFormatException e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player p = plugin.getServer().getPlayer(player.getUniqueId());
                    if (p != null) {
                        p.sendMessage("§c[BlockStreet] Invalid quantity. Please enter a whole number, or type §4cancel§c.");
                    }
                });
                return;
            }

            if (qty <= 0) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player p = plugin.getServer().getPlayer(player.getUniqueId());
                    if (p != null) {
                        p.sendMessage("§c[BlockStreet] Quantity must be at least 1.");
                    }
                });
                return;
            }

            catcher.recordQuantity(qty);
            // Remove the catcher BEFORE handing off to Main Thread to prevent re-entry
            guiManager.removeChatCatcher(player.getUniqueId());

            // Hand off to Main Thread: submit order + reopen market GUI
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    guiManager.completeChatInput(catcher));
        }
    }

    // ─────────────────────────── Player quit ──────────────────────────────────────

    /**
     * Cleans up all GUI state when a player disconnects.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        guiManager.handlePlayerQuit(event.getPlayer().getUniqueId());
    }
}
