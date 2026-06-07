package com.perseusj.blockstreet.listeners;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.gui.ChatInputCatcher;
import com.perseusj.blockstreet.managers.MailboxManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Handles player lifecycle events relevant to BlockStreet.
 *
 * <h2>Mailbox Delivery (PlayerJoin — E1, E2)</h2>
 * When a player joins, any pending mailbox items (stored because the player was offline
 * during a fill, or their inventory was full) are delivered via
 * {@link MailboxManager#deliverPendingItems}.
 *
 * <h2>Chat-Catcher Cleanup (PlayerQuit — E19)</h2>
 * If a player disconnects while a {@link ChatInputCatcher} is active (waiting for price/qty
 * input via chat), the catcher is removed to prevent orphan listeners. Because Phase 2A
 * asset locking does not occur until {@code completeChatInput} is called at the end of the
 * chat flow, no asset rollback is needed at this stage — the player's funds/items are safe.
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
        // Deliver any items that were queued while the player was offline or inventory-full
        MailboxManager.getInstance().deliverPendingItems(event.getPlayer());
    }

    // ──────────────────────────── PlayerQuitEvent ────────────────────────────────

    /**
     * Cleans up all BlockStreet state for a disconnecting player.
     *
     * <ol>
     *   <li>If a {@link ChatInputCatcher} was active for this player, remove it so it
     *       cannot fire after the player has left (prevents orphan listener).</li>
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
            // Remove any pending chat catcher (no asset lock to roll back — see javadoc above)
            ChatInputCatcher catcher = guiManager.removeChatCatcher(playerId);
            if (catcher != null) {
                plugin.getLogger().fine("[BlockStreet] Removed dangling ChatInputCatcher for "
                        + event.getPlayer().getName() + " on disconnect.");
            }

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