package com.perseusj.blockstreet.gui.tabs;

import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.engine.OrderSubmissionService;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderType;
import com.perseusj.blockstreet.gui.ChatInputContext;
import com.perseusj.blockstreet.gui.ChatInputManager;
import com.perseusj.blockstreet.gui.GuiManager;
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
import java.util.Arrays;
import java.util.List;

/**
 * Tab 2 — Sell / Quick Sell
 *
 * <p>Renders the player's own inventory contents into slots 9–43 (the main content area),
 * mirroring what the player is carrying. Items that are not tradeable on the market are
 * shown as gray glass panes with a "Not tradeable" lore. Clicking a tradeable item opens
 * a 27-slot sub-panel with two choices:
 * <ul>
 *   <li>Slot 11 — <strong>Quick Sell</strong>: instantly sells to the best resting Buy Order.</li>
 *   <li>Slot 15 — <strong>Create Sell Order</strong>: prompts for price then duration via chat.</li>
 * </ul>
 *
 * <h2>Slot layout</h2>
 * <pre>
 *   Content area: slots 9–43  (5 rows × 7 columns = 35 slots)
 *   Player inv rows 0–3 mapped to GUI rows 1–4 (left 9 columns ignored for tabs)
 * </pre>
 *
 * <p><strong>Main Thread only.</strong>
 */
public class SellTab implements GuiTabHandler {

    // ── Constants ───────────────────────────────────────────────────────────────

    /** First slot of the content area. */
    private static final int CONTENT_START = 9;
    /** Last slot of the content area (inclusive). */
    private static final int CONTENT_END   = 43;
    /** Total slots available for the virtual inventory mirror. */
    private static final int CONTENT_SLOTS = CONTENT_END - CONTENT_START + 1; // 35

    private static final Material NOT_TRADEABLE_FILL = Material.GRAY_STAINED_GLASS_PANE;

    // ── Dependencies ────────────────────────────────────────────────────────────

    private final ConfigManager config;
    private final GuiManager    guiManager;

    public SellTab(ConfigManager config, GuiManager guiManager) {
        this.config     = config;
        this.guiManager = guiManager;
    }

    // ── Render ──────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiSession session) {
        Inventory inv    = session.getInventory();
        Player    player = session.getPlayer();

        // Grab the player's inventory contents (36 slots: hotbar 0–8, main 9–35).
        // We display up to CONTENT_SLOTS entries in GUI slots 9–43.
        ItemStack[] playerInv = player.getInventory().getContents();

        for (int i = 0; i < CONTENT_SLOTS; i++) {
            int guiSlot = CONTENT_START + i;

            if (i >= playerInv.length) {
                inv.setItem(guiSlot, makeFiller());
                continue;
            }

            ItemStack original = playerInv[i];
            if (original == null || original.getType() == Material.AIR) {
                inv.setItem(guiSlot, null); // empty slot
                continue;
            }

            // Check if tradeable
            boolean tradeable = isTradeable(original);
            if (tradeable) {
                // Display a clean copy with extra lore; keep original amount
                ItemStack display = original.clone();
                appendSellLore(display);
                inv.setItem(guiSlot, display);
            } else {
                // Grey glass pane with "Not tradeable" tooltip
                inv.setItem(guiSlot, makeNotTradeableSlot(original));
            }
        }
    }

    // ── Click ───────────────────────────────────────────────────────────────────

    @Override
    public void onClick(InventoryClickEvent event, GuiSession session) {
        if (event.getClickedInventory() == null) return;

        // Only handle clicks within the GUI (top inventory), not the player's real inv below
        if (!event.getClickedInventory().equals(session.getInventory())) return;

        if (session.isSubPanelOpen()) {
            onSubPanelClick(event, session);
            return;
        }

        int slot = event.getRawSlot();
        if (slot < CONTENT_START || slot > CONTENT_END) return;

        int playerInvIndex = slot - CONTENT_START;
        ItemStack[] playerInv = session.getPlayer().getInventory().getContents();
        if (playerInvIndex >= playerInv.length) return;

        ItemStack original = playerInv[playerInvIndex];
        if (original == null || original.getType() == Material.AIR) return;

        // Resolve asset symbol
        AssetConfig asset = findAsset(original);
        if (asset == null) {
            session.getPlayer().sendMessage("§c[BlockStreet] That item cannot be sold on the market.");
            return;
        }

        openSubPanel(session, asset, original);
    }

    // ── Sub-panel (Quick Sell / Create Sell Order) ───────────────────────────

    /**
     * Opens a 27-slot sub-panel inventory with two action buttons for the chosen item.
     *
     * <pre>
     *   Slot 11: [Quick Sell]         — Sell at market price (best Buy Order)
     *   Slot 13: [Item Preview]       — The item the player clicked
     *   Slot 15: [Create Sell Order]  — Enter custom price + duration via chat
     * </pre>
     */
    private void openSubPanel(GuiSession session, AssetConfig asset, ItemStack original) {
        Player player = session.getPlayer();

        // Estimate best bid for lore preview (best resting buy order price from engine)
        double bestBid = guiManager.getEngine().getBestBid(asset.getSymbol());
        double setupFeeRate = config.getSetupFeeRate();
        double taxRate      = config.getStandardTaxRate();

        // Quick Sell: net payout = bestBid × qty × (1 - taxRate) — no setup fee for market orders
        int qty = original.getAmount();
        double quickSellNet = bestBid > 0
                ? bestBid * qty * (1.0 - taxRate)
                : 0.0;

        Inventory subInv = Bukkit.createInventory(null, 27,
                "§8Sell: §f" + asset.getDisplayName());

        // Slot 13 — Item preview (centre)
        ItemStack preview = original.clone();
        ItemMeta previewMeta = preview.getItemMeta();
        if (previewMeta != null) {
            previewMeta.setDisplayName("§f" + asset.getDisplayName() + " §7(×" + qty + ")");
            previewMeta.setLore(List.of("§7Selected item"));
            preview.setItemMeta(previewMeta);
        }
        subInv.setItem(13, preview);

        // Slot 11 — Quick Sell button
        ItemStack quickSellBtn = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta quickMeta = quickSellBtn.getItemMeta();
        if (quickMeta != null) {
            quickMeta.setDisplayName("§6§lQuick Sell");
            List<String> lore = new ArrayList<>();
            if (bestBid > 0) {
                lore.add("§7Best bid: §f" + String.format("%.2f", bestBid) + " each");
                lore.add("§7Sales tax: §c-" + (int) (taxRate * 100) + "%");
                lore.add("§aNet payout: §f" + String.format("%.2f", quickSellNet));
            } else {
                lore.add("§cNo buyers found at this time.");
            }
            lore.add("");
            lore.add("§eLeft-click to sell instantly.");
            quickMeta.setLore(lore);
            quickSellBtn.setItemMeta(quickMeta);
        }
        subInv.setItem(11, quickSellBtn);

        // Slot 15 — Create Sell Order button
        ItemStack limitBtn = new ItemStack(Material.PAPER);
        ItemMeta limitMeta = limitBtn.getItemMeta();
        if (limitMeta != null) {
            limitMeta.setDisplayName("§a§lCreate Sell Order");
            limitMeta.setLore(List.of(
                    "§7Set a custom price and duration.",
                    "§7Setup fee: §c" + (int) (setupFeeRate * 100) + "%",
                    "",
                    "§eLeft-click to configure."
            ));
            limitBtn.setItemMeta(limitMeta);
        }
        subInv.setItem(15, limitBtn);

        // Store sub-panel state on the session so the click handler can route correctly
        session.setSubPanelOpen(true);
        session.setSubPanelAsset(asset.getSymbol());
        session.setSubPanelItem(original);

        // Replace the session's active inventory reference so handleClose / handleClick works
        session.setInventory(subInv);
        player.openInventory(subInv);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private boolean isTradeable(ItemStack item) {
        return findAsset(item) != null;
    }

    private AssetConfig findAsset(ItemStack item) {
        for (AssetConfig a : config.getAllAssets().values()) {
            if (a.getMaterial() == item.getType() && a.isEnabled()) {
                return a;
            }
        }
        return null;
    }

    private void appendSellLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();
        lore.add("");
        lore.add("§eLeft-click to sell this item.");
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private ItemStack makeNotTradeableSlot(ItemStack original) {
        ItemStack pane = new ItemStack(NOT_TRADEABLE_FILL);
        ItemMeta meta  = pane.getItemMeta();
        if (meta != null) {
            String name = original.getType().name().replace('_', ' ');
            meta.setDisplayName("§8" + name);
            meta.setLore(List.of("§7Not tradeable on BlockStreet"));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    private ItemStack makeFiller() {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta  = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        return pane;
    }

    // ── Sub-panel click routing ─────────────────────────────────────────────────

    /**
     * Handles clicks inside the 27-slot sub-panel opened by {@link #openSubPanel}.
     * Called by GuiManager when the session has {@code subPanelOpen == true}.
     */
    public void onSubPanelClick(InventoryClickEvent event, GuiSession session) {
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(session.getInventory())) return;
        event.setCancelled(true);

        int slot   = event.getRawSlot();
        Player player = session.getPlayer();
        String symbol = session.getSubPanelAsset();
        ItemStack original = session.getSubPanelItem();
        if (symbol == null || original == null) return;

        ChatInputManager chatInputManager =
                com.perseusj.blockstreet.BlockStreet.getInstance().getChatInputManager();

        if (slot == 11) {
            // Quick Sell — submit a MARKET SELL order immediately
            session.setSubPanelOpen(false);
            session.setSubPanelAsset(null);
            session.setSubPanelItem(null);
            player.closeInventory();
            guiManager.getOrderSubmissionService().submitOrder(
                    player, symbol, OrderSide.SELL, OrderType.MARKET, 0.0, original.getAmount());

        } else if (slot == 15) {
            // Create Sell Order — enter price then duration via chat
            session.setSubPanelOpen(false);
            session.setSubPanelAsset(null);
            session.setSubPanelItem(null);
            player.closeInventory();
            session.setAwaitingChatInput(true);

            final String finalSymbol = symbol;
            final int finalQty = original.getAmount();

            chatInputManager.awaitInput(player, new ChatInputContext() {
                @Override
                public String prompt() {
                    return "§6[BlockStreet] §fEnter PRICE for " + finalSymbol
                            + " ×" + finalQty + " (or type §ccancel§f):";
                }

                @Override
                public void resolve(String input, Player p) throws NumberFormatException {
                    if (input.equalsIgnoreCase("cancel")) {
                        session.setAwaitingChatInput(false);
                        p.sendMessage("§e[BlockStreet] Order entry cancelled.");
                        guiManager.openGui(p);
                        return;
                    }
                    double price = Double.parseDouble(input);
                    if (price <= 0) throw new NumberFormatException("Price must be positive");

                    // Second prompt: duration
                    List<Integer> durations = config.getAllowedDurations();
                    String durationHint = durations.toString().replace("[", "").replace("]", "");
                    chatInputManager.awaitInput(p, new ChatInputContext() {
                        @Override
                        public String prompt() {
                            return "§6[BlockStreet] §fEnter DURATION in days [" + durationHint
                                    + "] (or type §ccancel§f):";
                        }

                        @Override
                        public void resolve(String input2, Player p2) throws NumberFormatException {
                            if (input2.equalsIgnoreCase("cancel")) {
                                session.setAwaitingChatInput(false);
                                p2.sendMessage("§e[BlockStreet] Order entry cancelled.");
                                guiManager.openGui(p2);
                                return;
                            }
                            int days = Integer.parseInt(input2);
                            if (days <= 0) throw new NumberFormatException("Duration must be positive");

                            session.setAwaitingChatInput(false);
                            guiManager.getOrderSubmissionService().submitOrder(
                                    p2, finalSymbol, OrderSide.SELL, OrderType.LIMIT, price, finalQty);
                            guiManager.openGui(p2);
                        }
                    });
                }
            });
        }
    }
}
