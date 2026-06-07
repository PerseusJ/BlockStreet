package com.perseusj.blockstreet.managers;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Stores items on behalf of offline players (or players with full inventories) until
 * they can be delivered.
 *
 * <h2>Responsibility Boundary</h2>
 * This is the <em>in-memory</em> mailbox. Phase 4 (DatabaseService) will extend this
 * with persistence via the {@code mailbox_items} table so items survive server restarts.
 * For now, items stored here are durable only for the current server session.
 *
 * <h2>Delivery Flow</h2>
 * <ol>
 *   <li>{@link com.perseusj.blockstreet.engine.SettlementDispatcher} calls {@link #storeItems}
 *       when inventory delivery fails (inventory full) or the player is offline.</li>
 *   <li>On {@code PlayerJoinEvent}, {@link #deliverPendingItems(Player)} is called to flush
 *       all pending stacks into the player's inventory.</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * {@link #storeItems} may be called from the Main Thread (via SettlementDispatcher callback).
 * {@link #deliverPendingItems} is always called on the Main Thread (from PlayerJoinEvent).
 * The internal map uses {@link ConcurrentHashMap} for thread-safe structural modifications.
 */
public final class MailboxManager {

    private static MailboxManager INSTANCE;

    private final Logger logger;

    /** Maps player UUID → ordered list of pending ItemStacks to deliver. */
    private final Map<UUID, List<ItemStack>> pendingDeliveries = new ConcurrentHashMap<>();

    // ──────────────────────────── Singleton ──────────────────────────────────────

    private MailboxManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Initializes and returns the singleton instance. Must be called during plugin enable
     * before any other access.
     */
    public static MailboxManager init(Logger logger) {
        INSTANCE = new MailboxManager(logger);
        return INSTANCE;
    }

    /**
     * Returns the singleton instance. Throws if {@link #init} has not been called.
     */
    public static MailboxManager getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("MailboxManager has not been initialized.");
        }
        return INSTANCE;
    }

    // ──────────────────────────── Storage ────────────────────────────────────────

    /**
     * Queues one or more ItemStacks for delivery to a player.
     *
     * <p>Thread-safe. Called by {@link com.perseusj.blockstreet.engine.SettlementDispatcher}
     * when the primary delivery attempt fails.
     *
     * @param playerId the recipient player's UUID
     * @param items    the items to hold (may contain multiple stacks)
     */
    public void storeItems(UUID playerId, Collection<ItemStack> items) {
        if (items == null || items.isEmpty()) return;
        pendingDeliveries
                .computeIfAbsent(playerId, id -> Collections.synchronizedList(new ArrayList<>()))
                .addAll(items);
        logger.fine("[MailboxManager] Stored " + items.size() + " item stack(s) for player " + playerId);
    }

    // ──────────────────────────── Delivery ───────────────────────────────────────

    /**
     * Attempts to deliver all pending items to the given online player.
     *
     * <p><strong>Main Thread only.</strong> Called from {@code PlayerJoinEvent}.
     *
     * <p>Items that still don't fit (inventory too full even after join) are re-stored.
     * The player is notified in both cases.
     *
     * @param player the online player to deliver to
     */
    public void deliverPendingItems(Player player) {
        List<ItemStack> pending = pendingDeliveries.remove(player.getUniqueId());
        if (pending == null || pending.isEmpty()) return;

        List<ItemStack> stillPending = new ArrayList<>();
        for (ItemStack item : pending) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            if (!overflow.isEmpty()) {
                // Inventory still full for some stacks; re-queue
                stillPending.addAll(overflow.values());
            }
        }

        if (!stillPending.isEmpty()) {
            pendingDeliveries
                    .computeIfAbsent(player.getUniqueId(),
                            id -> Collections.synchronizedList(new ArrayList<>()))
                    .addAll(stillPending);
            player.sendMessage("§e[BlockStreet] §fYour inventory is full. " +
                    stillPending.size() + " item stack(s) are still in your mailbox.");
        }

        int delivered = pending.size() - stillPending.size();
        if (delivered > 0) {
            player.sendMessage("§a[BlockStreet] §f" + delivered +
                    " item stack(s) from your mailbox have been delivered to your inventory.");
        }
    }

    /**
     * Returns {@code true} if the given player has pending mailbox items.
     */
    public boolean hasPendingItems(UUID playerId) {
        List<ItemStack> pending = pendingDeliveries.get(playerId);
        return pending != null && !pending.isEmpty();
    }

    /**
     * Returns the number of item stacks pending for the given player (0 if none).
     */
    public int getPendingItemCount(UUID playerId) {
        List<ItemStack> pending = pendingDeliveries.get(playerId);
        return pending == null ? 0 : pending.size();
    }
}
