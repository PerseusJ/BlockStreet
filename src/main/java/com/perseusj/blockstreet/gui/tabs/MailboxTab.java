package com.perseusj.blockstreet.gui.tabs;

import com.perseusj.blockstreet.db.MailboxLedgerEntry;
import com.perseusj.blockstreet.db.MailboxLedgerService;
import com.perseusj.blockstreet.gui.GuiSession;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab 5 — Mailbox
 *
 * <p>Loads and displays all unclaimed {@link MailboxLedgerEntry} objects in the content
 * area (slots 9–43). Players can click an individual entry to claim it one-by-one, or
 * click the <strong>[Claim All]</strong> button at slot 45 to claim everything at once.
 *
 * <h2>Slot layout</h2>
 * <pre>
 *   Slots 9–43  — Unclaimed entries (ITEM: real icon; CURRENCY: gold ingot with lore)
 *   Slot 45     — [Claim All] button (nether star / green wool)
 *   Slot 53     — Close button
 * </pre>
 *
 * <h2>Entry rendering</h2>
 * <ul>
 *   <li><b>ITEM</b> entries: the actual ItemStack icon, with lore showing
 *       source, quantity, and "Unclaimed" status.</li>
 *   <li><b>CURRENCY</b> entries: a Gold Ingot with lore showing the pending payout
 *       and the source event (FILL / CANCEL / EXPIRATION).</li>
 * </ul>
 *
 * <h2>Loading strategy</h2>
 * On {@link #render}, we fire an async DB load and store the result in the session's
 * mailbox entry cache. The render pass then reads from the cache, so the first open
 * may be empty for one tick; a subsequent GUI refresh (from GuiManager's timer) will
 * populate the slots once the async load completes.
 *
 * <p><strong>Main Thread only.</strong>
 */
public class MailboxTab implements GuiTabHandler {

    // ── Slot constants ───────────────────────────────────────────────────────────
    private static final int CONTENT_START  = 9;
    private static final int CONTENT_END    = 43;
    private static final int CONTENT_SLOTS  = CONTENT_END - CONTENT_START + 1; // 35
    private static final int SLOT_CLAIM_ALL = 45;
    private static final int SLOT_CLOSE     = 53;

    // ── Dependencies ────────────────────────────────────────────────────────────
    private final MailboxLedgerService mailboxLedgerService;
    private final Plugin               plugin;

    public MailboxTab(MailboxLedgerService mailboxLedgerService, Plugin plugin) {
        this.mailboxLedgerService = mailboxLedgerService;
        this.plugin               = plugin;
    }

    // ── Render ──────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiSession session) {
        Inventory inv    = session.getInventory();
        Player    player = session.getPlayer();

        // ── Async load entries into session cache ─────────────────────────────
        // If the cache is null or stale, kick off an async load.
        if (session.getMailboxEntries() == null) {
            session.setMailboxEntries(List.of()); // Placeholder so we don't queue twice
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<MailboxLedgerEntry> entries =
                        mailboxLedgerService.loadUnclaimed(player.getUniqueId());
                // Switch back to main thread to update the inventory
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    session.setMailboxEntries(entries);
                    renderEntries(session);
                    player.updateInventory();
                });
            });
        }

        // Render whatever is currently cached (may be empty on first open)
        renderEntries(session);

        // ── Claim All button ──────────────────────────────────────────────────
        ItemStack claimAllBtn = new ItemStack(Material.NETHER_STAR);
        ItemMeta claimMeta = claimAllBtn.getItemMeta();
        if (claimMeta != null) {
            claimMeta.setDisplayName("§e§l[Claim All]");
            claimMeta.setLore(List.of(
                    "§7Claim all pending items and currency",
                    "§7from your mailbox at once.",
                    "",
                    "§eLeft-click to claim everything."
            ));
            claimAllBtn.setItemMeta(claimMeta);
        }
        inv.setItem(SLOT_CLAIM_ALL, claimAllBtn);

        // ── Close button ──────────────────────────────────────────────────────
        ItemStack closeBtn = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeBtn.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName("§cClose");
            closeBtn.setItemMeta(closeMeta);
        }
        inv.setItem(SLOT_CLOSE, closeBtn);
    }

    /**
     * Populates slots 9–43 with the cached mailbox entries.
     * Called from both {@link #render} and the async-load completion callback.
     */
    private void renderEntries(GuiSession session) {
        Inventory inv = session.getInventory();
        List<MailboxLedgerEntry> entries = session.getMailboxEntries();

        // Clear the content area first
        for (int s = CONTENT_START; s <= CONTENT_END; s++) {
            inv.setItem(s, null);
        }

        if (entries == null || entries.isEmpty()) {
            // Show an informational item in the centre
            ItemStack empty = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta m = empty.getItemMeta();
            if (m != null) {
                m.setDisplayName("§7Your mailbox is empty.");
                empty.setItemMeta(m);
            }
            inv.setItem(22, empty);
            return;
        }

        // Display up to CONTENT_SLOTS entries (paginated in the future if > 35)
        int displayCount = Math.min(entries.size(), CONTENT_SLOTS);
        for (int i = 0; i < displayCount; i++) {
            MailboxLedgerEntry entry = entries.get(i);
            int guiSlot = CONTENT_START + i;
            inv.setItem(guiSlot, buildEntryIcon(entry));
        }

        // If there are more entries than fit, show a count indicator
        if (entries.size() > CONTENT_SLOTS) {
            ItemStack more = new ItemStack(Material.PAPER);
            ItemMeta m = more.getItemMeta();
            if (m != null) {
                m.setDisplayName("§7+" + (entries.size() - CONTENT_SLOTS) + " more entries");
                m.setLore(List.of("§7Click §eClaim All§7 to claim everything."));
                more.setItemMeta(m);
            }
            inv.setItem(CONTENT_END, more);
        }
    }

    /**
     * Builds a display ItemStack for a single mailbox entry.
     *
     * <ul>
     *   <li>ITEM entries: real item icon with source/qty lore.</li>
     *   <li>CURRENCY entries: Gold Ingot with amount/source lore.</li>
     * </ul>
     */
    private ItemStack buildEntryIcon(MailboxLedgerEntry entry) {
        if (entry.type() == MailboxLedgerEntry.EntryType.CURRENCY) {
            ItemStack ingot = new ItemStack(Material.GOLD_INGOT);
            ItemMeta  m     = ingot.getItemMeta();
            if (m != null) {
                m.setDisplayName("§6§lCurrency Payout");
                List<String> lore = new ArrayList<>();
                lore.add("§a+" + String.format("%.2f", entry.amount()) + " pending");
                lore.add("§7Source: §f" + entry.source());
                lore.add("");
                lore.add("§7Status: §eUnclaimed");
                lore.add("§eLeft-click to claim.");
                m.setLore(lore);
                ingot.setItemMeta(m);
            }
            return ingot;
        } else {
            // ITEM entry: try to reconstruct the icon; fall back to a chest on failure
            ItemStack icon = tryDeserializeIcon(entry);
            if (icon == null) {
                icon = new ItemStack(Material.CHEST);
            }
            ItemMeta m = icon.getItemMeta();
            if (m == null) {
                m = org.bukkit.Bukkit.getItemFactory().getItemMeta(icon.getType());
            }
            if (m != null) {
                List<String> lore = new ArrayList<>();
                if (m.hasLore() && m.getLore() != null) {
                    lore.addAll(m.getLore());
                    lore.add("");
                }
                lore.add("§7Qty: §f" + entry.itemQty());
                lore.add("§7Source: §f" + entry.source());
                lore.add("§7Status: §eUnclaimed");
                lore.add("§eLeft-click to claim.");
                m.setLore(lore);
                icon.setItemMeta(m);
            }
            return icon;
        }
    }

    /**
     * Attempts to deserialize the item NBT from a {@link MailboxLedgerEntry}.
     * Returns {@code null} if the NBT is missing or deserialization fails.
     */
    private ItemStack tryDeserializeIcon(MailboxLedgerEntry entry) {
        if (entry.itemNbt() == null || entry.itemNbt().isBlank()) return null;
        try (java.io.ByteArrayInputStream bis =
                     new java.io.ByteArrayInputStream(
                             java.util.Base64.getDecoder().decode(entry.itemNbt()));
             org.bukkit.util.io.BukkitObjectInputStream ois =
                     new org.bukkit.util.io.BukkitObjectInputStream(bis)) {
            return (ItemStack) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Click ────────────────────────────────────────────────────────────────────

    @Override
    public void onClick(InventoryClickEvent event, GuiSession session) {
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(session.getInventory())) return;

        int slot = event.getRawSlot();
        Player player = session.getPlayer();

        if (slot == SLOT_CLAIM_ALL) {
            // Claim all — invalidate cache after so next open re-loads
            session.setMailboxEntries(null);
            player.sendMessage("§e[BlockStreet] Processing mailbox claim...");
            mailboxLedgerService.claimAll(player);
            return;
        }

        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        // Content area: claim individual entry
        if (slot >= CONTENT_START && slot <= CONTENT_END) {
            List<MailboxLedgerEntry> entries = session.getMailboxEntries();
            if (entries == null) return;

            int entryIndex = slot - CONTENT_START;
            if (entryIndex >= entries.size()) return;

            MailboxLedgerEntry entry = entries.get(entryIndex);

            // Optimistically remove from the local cache to update the display immediately
            List<MailboxLedgerEntry> updatedEntries = new ArrayList<>(entries);
            updatedEntries.remove(entryIndex);
            session.setMailboxEntries(updatedEntries);
            renderEntries(session);
            player.updateInventory();

            // Delegate to service (handles DB update + delivery with exploit guard)
            mailboxLedgerService.claimEntry(entry.id(), player);
        }
    }
}
