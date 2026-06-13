package com.perseusj.blockstreet.db;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Replaces MailboxManager.
 * Unified item + currency ledger with duplication exploit guards.
 */
public final class MailboxLedgerService {

    private final Plugin plugin;
    private final DatabaseService db;
    private final Economy economy;
    private final Logger logger;
    private final DbWriteQueue writeQueue;

    /**
     * Players whose claim operation is currently in-flight (entries loaded but DB not yet updated).
     * Checked and written exclusively on the Server Main Thread — no external synchronisation needed.
     */
    private final Set<UUID> activeClaimingPlayers = ConcurrentHashMap.newKeySet();

    public MailboxLedgerService(Plugin plugin, DatabaseService db, Economy economy, DbWriteQueue writeQueue, Logger logger) {
        this.plugin = plugin;
        this.db = db;
        this.economy = economy;
        this.writeQueue = writeQueue;
        this.logger = logger;
    }

    public void addCurrencyEntry(UUID playerId, double amount, String source, UUID orderId) {
        writeQueue.offer(conn -> {
            String sql = """
                INSERT INTO mailbox_ledger (player_id, entry_type, amount, source, order_id, stored_at)
                VALUES (?, 'CURRENCY', ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.setDouble(2, amount);
                ps.setString(3, source);
                ps.setString(4, orderId != null ? orderId.toString() : null);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    private String serializeItem(ItemStack item) {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             org.bukkit.util.io.BukkitObjectOutputStream oos = new org.bukkit.util.io.BukkitObjectOutputStream(bos)) {
            oos.writeObject(item);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            logger.severe("Failed to serialize item: " + e.getMessage());
            return "";
        }
    }

    private ItemStack deserializeItem(String base64) {
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(Base64.getDecoder().decode(base64));
             org.bukkit.util.io.BukkitObjectInputStream ois = new org.bukkit.util.io.BukkitObjectInputStream(bis)) {
            return (ItemStack) ois.readObject();
        } catch (Exception e) {
            logger.severe("Failed to deserialize item: " + e.getMessage());
            return null;
        }
    }

    public void addItemEntry(UUID playerId, ItemStack item, String source, UUID orderId) {
        String nbtBase64 = serializeItem(item);
        int qty = item.getAmount();

        writeQueue.offer(conn -> {
            String sql = """
                INSERT INTO mailbox_ledger (player_id, entry_type, item_nbt, item_qty, source, order_id, stored_at)
                VALUES (?, 'ITEM', ?, ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.setString(2, nbtBase64);
                ps.setInt(3, qty);
                ps.setString(4, source);
                ps.setString(5, orderId != null ? orderId.toString() : null);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    public List<MailboxLedgerEntry> loadUnclaimed(UUID playerId) {
        List<MailboxLedgerEntry> entries = new ArrayList<>();
        String sql = """
            SELECT id, entry_type, amount, item_nbt, item_qty, source, order_id, stored_at
            FROM mailbox_ledger
            WHERE player_id = ? AND claimed = 0
            ORDER BY stored_at ASC
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new MailboxLedgerEntry(
                            rs.getLong("id"),
                            playerId,
                            MailboxLedgerEntry.EntryType.valueOf(rs.getString("entry_type")),
                            rs.getDouble("amount"),
                            rs.getString("item_nbt"),
                            rs.getInt("item_qty"),
                            rs.getString("source"),
                            rs.getString("order_id"),
                            rs.getLong("stored_at")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.severe("[BlockStreet] Failed to load unclaimed ledger entries for " + playerId + ": " + e.getMessage());
        }
        return entries;
    }

    public void claimAll(Player player) {
        UUID uuid = player.getUniqueId();
        if (!activeClaimingPlayers.add(uuid)) {
            player.sendMessage("§e[BlockStreet] §fYour previous claim is still processing. Please wait.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Load entries async
            List<MailboxLedgerEntry> entries = loadUnclaimed(uuid);
            if (entries.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    activeClaimingPlayers.remove(uuid);
                    player.sendMessage("§c[BlockStreet] Your mailbox is empty.");
                });
                return;
            }

            try {
                // Mark all claimed in DB
                String sql = "UPDATE mailbox_ledger SET claimed = 1 WHERE player_id = ? AND claimed = 0";
                try (Connection conn = db.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                logger.severe("[BlockStreet] Failed to mark all claimed for " + uuid + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    activeClaimingPlayers.remove(uuid);
                    player.sendMessage("§c[BlockStreet] Database error occurred while claiming.");
                });
                return;
            }

            // DB write succeeded; deliver on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                activeClaimingPlayers.remove(uuid);
                deliverEntries(player, entries);
            });
        });
    }

    public void claimEntry(long entryId, Player player) {
        UUID uuid = player.getUniqueId();
        if (!activeClaimingPlayers.add(uuid)) {
            player.sendMessage("§e[BlockStreet] §fYour previous claim is still processing. Please wait.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Load specific entry
            List<MailboxLedgerEntry> entries = loadUnclaimed(uuid);
            MailboxLedgerEntry target = null;
            for (MailboxLedgerEntry e : entries) {
                if (e.id() == entryId) {
                    target = e;
                    break;
                }
            }

            if (target == null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    activeClaimingPlayers.remove(uuid);
                    player.sendMessage("§c[BlockStreet] Entry not found or already claimed.");
                });
                return;
            }

            final MailboxLedgerEntry finalTarget = target;
            try {
                // Mark claimed in DB
                String sql = "UPDATE mailbox_ledger SET claimed = 1 WHERE id = ? AND claimed = 0";
                try (Connection conn = db.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, entryId);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        // Already claimed?
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            activeClaimingPlayers.remove(uuid);
                            player.sendMessage("§c[BlockStreet] Entry already claimed.");
                        });
                        return;
                    }
                }
            } catch (SQLException e) {
                logger.severe("[BlockStreet] Failed to mark entry " + entryId + " claimed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    activeClaimingPlayers.remove(uuid);
                    player.sendMessage("§c[BlockStreet] Database error occurred while claiming.");
                });
                return;
            }

            // Deliver on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                activeClaimingPlayers.remove(uuid);
                deliverEntries(player, List.of(finalTarget));
            });
        });
    }

    private void deliverEntries(Player player, List<MailboxLedgerEntry> entries) {
        int itemsGiven = 0;
        double cashGiven = 0.0;

        for (MailboxLedgerEntry e : entries) {
            if (e.type() == MailboxLedgerEntry.EntryType.CURRENCY) {
                economy.depositPlayer(Bukkit.getOfflinePlayer(player.getUniqueId()), e.amount());
                cashGiven += e.amount();
            } else if (e.type() == MailboxLedgerEntry.EntryType.ITEM) {
                try {
                    ItemStack item = deserializeItem(e.itemNbt());
                    if (item != null) {
                        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                        if (!overflow.isEmpty()) {
                            overflow.values().forEach(s -> player.getWorld().dropItemNaturally(player.getLocation(), s));
                        }
                        itemsGiven++;
                    }
                } catch (Exception ex) {
                    logger.severe("[BlockStreet] Failed to deserialize item for entry " + e.id() + ": " + ex.getMessage());
                }
            }
        }

        if (cashGiven > 0 || itemsGiven > 0) {
            player.sendMessage(String.format("§a[BlockStreet] Claimed: §f%d item(s) and %s", itemsGiven, economy.format(cashGiven)));
        }
    }
}
