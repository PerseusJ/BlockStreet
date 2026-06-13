package com.perseusj.blockstreet.listeners;

import com.perseusj.blockstreet.BlockStreet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import com.perseusj.blockstreet.items.MarketLedgerItem;

import java.util.UUID;

/**
 * Handles player lifecycle events relevant to BlockStreet.
 *
 * <h2>Chat-Catcher Cleanup (PlayerQuit — E19)</h2>
 * If a player disconnects, the GUI manager cleans up active inputs and GUI sessions.
 *
 * <p>The {@link com.perseusj.blockstreet.gui.GuiManager#handlePlayerQuit} call also
 * clears the open GUI session, pending side, and active-orders cache — ensuring no
 * stale GUI state leaks after the player disconnects.
 */
public class PlayerListener implements Listener {

    private final BlockStreet plugin;

    public PlayerListener(BlockStreet plugin) {
        this.plugin = plugin;
    }

    // ──────────────────────────── PlayerJoinEvent ────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (plugin.getPlayerCacheDao() != null) {
                plugin.getPlayerCacheDao().updatePlayerCache(player.getUniqueId(), player.getName());
            }
        });

        // Prompt for resource pack (optional polish for Phase 3)
        // A real implementation might read a URL from config
        // player.setResourcePack("https://example.com/blockstreet-pack.zip");
    }

    // ──────────────────────────── PlayerInteractEvent ──────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getItem() == null) return;
        if (!MarketLedgerItem.isMarketLedger(event.getItem())) return;
        event.setCancelled(true);   // suppress default book-open behaviour
        // Open the book programmatically so the player sees the clickable pages
        event.getPlayer().openBook(event.getItem());
    }

    // ──────────────────────────── Resource Pack Event ──────────────────────────

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        boolean accepted = event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED
                || event.getStatus() == PlayerResourcePackStatusEvent.Status.ACCEPTED;
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (plugin.getPlayerCacheDao() != null) {
                plugin.getPlayerCacheDao().updateResourcePackStatus(event.getPlayer().getUniqueId(), accepted);
            }
        });
        
        var guiManager = plugin.getGuiManager();
        if (guiManager != null) {
            var session = guiManager.getSession(event.getPlayer().getUniqueId());
            if (session != null) {
                session.setResourcePackAccepted(accepted);
            }
        }
    }

    // ──────────────────────────── PlayerQuitEvent ────────────────────────────────

    /**
     * Cleans up all BlockStreet state for a disconnecting player.
     *
     * <ol>
     *   <li>Clean up the open GUI session and supplementary maps via
     *       {@link com.perseusj.blockstreet.gui.GuiManager#handlePlayerQuit}.</li>
     * </ol>
     *
     * <p><strong>E19 note:</strong> In the current architecture, Phase 2A asset locking
     * (Vault withdraw / item removal) happens only after the chat-input flow is fully
     * complete on the Main Thread. A player who disconnects mid-chat-input never had
     * their assets locked, so no rollback is required here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        var guiManager = plugin.getGuiManager();
        if (guiManager != null) {
            // Clean up GUI session, pending side, active-orders cache
            guiManager.handlePlayerQuit(playerId);
        }

        // ── E19: Phase 2A rollback ──────────────────────────────────────────────
        // If the player disconnects while assets are locked but the order has NOT yet
        // entered the engine queue, refund their currency (BUY) or return their items
        // via the mailbox (SELL).  This call is a no-op if the player is not in that window.
        var oss = plugin.getOrderSubmissionService();
        if (oss != null) {
            boolean rolledBack = oss.rollbackPendingLock(playerId);
            if (rolledBack) {
                plugin.getLogger().warning("[BlockStreet] E19 rollback triggered for "
                        + event.getPlayer().getName()
                        + " — assets refunded after disconnect during Phase 2A lock.");
            }
        }
    }
}