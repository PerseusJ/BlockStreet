package com.perseusj.blockstreet.gui.tabs;

import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderType;
import com.perseusj.blockstreet.gui.ChatInputContext;
import com.perseusj.blockstreet.gui.ChatInputManager;
import com.perseusj.blockstreet.gui.GuiManager;
import com.perseusj.blockstreet.gui.GuiSession;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab 3 — Create Buy Order (form layout)
 *
 * <p>Rather than showing a list of assets, this tab shows a single form with
 * interactive fields that the player fills in before submitting a resting buy order.
 *
 * <h2>Slot layout (within 54-slot chest)</h2>
 * <pre>
 *   Slot 13 — Selected item icon  (click → item picker sub-panel)
 *   Slot 22 — Price per unit      (click → chat input)
 *   Slot 31 — Quantity            (click → chat input)
 *   Slot 40 — Duration            (click → cycle through allowed-durations)
 *   Slot 37 — Fee preview         (read-only paper: setup fee + total locked)
 *   Slot 43 — [Submit Buy Order]  (lime dye / emerald)
 * </pre>
 *
 * <p>Form state (selected symbol, price, qty, duration) is persisted on the
 * {@link GuiSession} via the {@code buyForm*} fields added to GuiSession.
 *
 * <p><strong>Main Thread only.</strong>
 */
public class BuyOrderTab implements GuiTabHandler {

    // ── Form slot constants ──────────────────────────────────────────────────────
    private static final int SLOT_ITEM     = 13;
    private static final int SLOT_PRICE    = 22;
    private static final int SLOT_QTY      = 31;
    private static final int SLOT_DURATION = 40;
    private static final int SLOT_FEE      = 37;
    private static final int SLOT_SUBMIT   = 43;

    // ── Dependencies ────────────────────────────────────────────────────────────
    private final ConfigManager config;
    private final GuiManager    guiManager;

    public BuyOrderTab(ConfigManager config, GuiManager guiManager) {
        this.config     = config;
        this.guiManager = guiManager;
    }

    // ── Render ──────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiSession session) {
        Inventory inv = session.getInventory();

        // Retrieve form state from session (defaults if not yet set)
        String  symbol   = session.getBuyFormSymbol();
        double  price    = session.getBuyFormPrice();
        int     qty      = session.getBuyFormQty();
        int     duration = session.getBuyFormDuration() > 0
                ? session.getBuyFormDuration()
                : config.getDefaultDurationDays();

        // Persist resolved duration back (initialise once)
        if (session.getBuyFormDuration() <= 0) {
            session.setBuyFormDuration(duration);
        }

        // ── Slot 13: Item picker ─────────────────────────────────────────────
        AssetConfig asset = symbol != null ? config.getAsset(symbol) : null;
        ItemStack itemSlot;
        if (asset != null) {
            itemSlot = new ItemStack(asset.getMaterial());
            ItemMeta m = itemSlot.getItemMeta();
            if (m != null) {
                m.setDisplayName("§f" + asset.getDisplayName());
                m.setLore(List.of("§7Selected item", "§eClick to change."));
                itemSlot.setItemMeta(m);
            }
        } else {
            itemSlot = new ItemStack(Material.BARRIER);
            ItemMeta m = itemSlot.getItemMeta();
            if (m != null) {
                m.setDisplayName("§cNo item selected");
                m.setLore(List.of("§7Click to pick an item."));
                itemSlot.setItemMeta(m);
            }
        }
        inv.setItem(SLOT_ITEM, itemSlot);

        // ── Slot 22: Price ───────────────────────────────────────────────────
        ItemStack priceSlot = new ItemStack(Material.GOLD_INGOT);
        ItemMeta priceMeta = priceSlot.getItemMeta();
        if (priceMeta != null) {
            priceMeta.setDisplayName("§6Price per unit");
            priceMeta.setLore(List.of(
                    "§7Current: §f" + (price > 0 ? String.format("%.2f", price) : "§cNot set"),
                    "",
                    "§eClick to enter price in chat."
            ));
            priceSlot.setItemMeta(priceMeta);
        }
        inv.setItem(SLOT_PRICE, priceSlot);

        // ── Slot 31: Quantity ────────────────────────────────────────────────
        ItemStack qtySlot = new ItemStack(Material.OAK_SIGN);
        ItemMeta qtyMeta = qtySlot.getItemMeta();
        if (qtyMeta != null) {
            qtyMeta.setDisplayName("§bQuantity");
            qtyMeta.setLore(List.of(
                    "§7Current: §f" + (qty > 0 ? qty : "§cNot set"),
                    "",
                    "§eClick to enter quantity in chat."
            ));
            qtySlot.setItemMeta(qtyMeta);
        }
        inv.setItem(SLOT_QTY, qtySlot);

        // ── Slot 40: Duration ────────────────────────────────────────────────
        ItemStack durSlot = new ItemStack(Material.CLOCK);
        ItemMeta durMeta = durSlot.getItemMeta();
        if (durMeta != null) {
            durMeta.setDisplayName("§eDuration");
            durMeta.setLore(List.of(
                    "§7Current: §f" + duration + " day(s)",
                    "§7Options: §f" + config.getAllowedDurations().toString()
                            .replace("[", "").replace("]", ""),
                    "",
                    "§eClick to cycle."
            ));
            durSlot.setItemMeta(durMeta);
        }
        inv.setItem(SLOT_DURATION, durSlot);

        // ── Slot 37: Fee preview (read-only) ─────────────────────────────────
        double setupFeeRate = config.getSetupFeeRate();
        double setupFee   = (price > 0 && qty > 0) ? price * qty * setupFeeRate : 0.0;
        double totalLock  = (price > 0 && qty > 0) ? price * qty + setupFee : 0.0;

        ItemStack feeSlot = new ItemStack(Material.PAPER);
        ItemMeta feeMeta = feeSlot.getItemMeta();
        if (feeMeta != null) {
            feeMeta.setDisplayName("§7Order Preview");
            List<String> feeLore = new ArrayList<>();
            feeLore.add("§7Setup fee: §c" + (price > 0 && qty > 0
                    ? String.format("%.2f", setupFee) : "N/A"));
            feeLore.add("§7Total locked: §f" + (totalLock > 0
                    ? String.format("%.2f", totalLock) : "N/A"));
            feeLore.add("");
            feeLore.add("§8(Setup fee is non-refundable)");
            feeMeta.setLore(feeLore);
            feeSlot.setItemMeta(feeMeta);
        }
        inv.setItem(SLOT_FEE, feeSlot);

        // ── Slot 43: Submit button ────────────────────────────────────────────
        boolean canSubmit = asset != null && price > 0 && qty > 0;
        ItemStack submitSlot = new ItemStack(canSubmit ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta submitMeta = submitSlot.getItemMeta();
        if (submitMeta != null) {
            submitMeta.setDisplayName(canSubmit ? "§a§l[Submit Buy Order]" : "§7[Submit Buy Order]");
            List<String> submitLore = new ArrayList<>();
            if (!canSubmit) {
                submitLore.add("§cFill in all fields first.");
            } else {
                submitLore.add("§7Item: §f" + asset.getDisplayName());
                submitLore.add("§7Price: §f" + String.format("%.2f", price));
                submitLore.add("§7Qty: §f" + qty);
                submitLore.add("§7Duration: §f" + duration + "d");
                submitLore.add("§7Total lock: §f" + String.format("%.2f", totalLock));
                submitLore.add("");
                submitLore.add("§eLeft-click to place order.");
            }
            submitMeta.setLore(submitLore);
            submitSlot.setItemMeta(submitMeta);
        }
        inv.setItem(SLOT_SUBMIT, submitSlot);
    }

    // ── Click ────────────────────────────────────────────────────────────────────

    @Override
    public void onClick(InventoryClickEvent event, GuiSession session) {
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(session.getInventory())) return;

        if (session.isItemPickerOpen()) {
            onItemPickerClick(event, session);
            return;
        }

        int slot = event.getRawSlot();
        Player player = session.getPlayer();
        ChatInputManager chatInputManager =
                com.perseusj.blockstreet.BlockStreet.getInstance().getChatInputManager();

        switch (slot) {

            case SLOT_ITEM -> openItemPicker(session, player);

            case SLOT_PRICE -> {
                session.setAwaitingChatInput(true);
                player.closeInventory();
                chatInputManager.awaitInput(player, new ChatInputContext() {
                    @Override public String prompt() {
                        return "§6[BlockStreet] §fEnter PRICE per unit (or type §ccancel§f):";
                    }
                    @Override public void resolve(String input, Player p) throws NumberFormatException {
                        if (input.equalsIgnoreCase("cancel")) {
                            session.setAwaitingChatInput(false);
                            p.sendMessage("§e[BlockStreet] Price entry cancelled.");
                            guiManager.openGui(p, com.perseusj.blockstreet.gui.GuiTab.BUY_ORDER);
                            return;
                        }
                        double price = Double.parseDouble(input);
                        if (price <= 0) throw new NumberFormatException("Price must be positive");
                        session.setBuyFormPrice(price);
                        session.setAwaitingChatInput(false);
                        guiManager.openGui(p, com.perseusj.blockstreet.gui.GuiTab.BUY_ORDER);
                    }
                });
            }

            case SLOT_QTY -> {
                session.setAwaitingChatInput(true);
                player.closeInventory();
                chatInputManager.awaitInput(player, new ChatInputContext() {
                    @Override public String prompt() {
                        return "§6[BlockStreet] §fEnter QUANTITY (or type §ccancel§f):";
                    }
                    @Override public void resolve(String input, Player p) throws NumberFormatException {
                        if (input.equalsIgnoreCase("cancel")) {
                            session.setAwaitingChatInput(false);
                            p.sendMessage("§e[BlockStreet] Quantity entry cancelled.");
                            guiManager.openGui(p, com.perseusj.blockstreet.gui.GuiTab.BUY_ORDER);
                            return;
                        }
                        int qty = Integer.parseInt(input);
                        if (qty <= 0) throw new NumberFormatException("Quantity must be positive");
                        session.setBuyFormQty(qty);
                        session.setAwaitingChatInput(false);
                        guiManager.openGui(p, com.perseusj.blockstreet.gui.GuiTab.BUY_ORDER);
                    }
                });
            }

            case SLOT_DURATION -> {
                // Cycle through allowed durations
                List<Integer> durations = config.getAllowedDurations();
                if (durations.isEmpty()) break;
                int current = session.getBuyFormDuration();
                int idx = durations.indexOf(current);
                int next = durations.get((idx + 1) % durations.size());
                session.setBuyFormDuration(next);
                // Re-render in place (no close needed)
                guiManager.renderSession(session);
                player.updateInventory();
            }

            case SLOT_SUBMIT -> {
                String  symbol   = session.getBuyFormSymbol();
                double  price    = session.getBuyFormPrice();
                int     qty      = session.getBuyFormQty();
                int     duration = session.getBuyFormDuration() > 0
                        ? session.getBuyFormDuration()
                        : config.getDefaultDurationDays();

                AssetConfig asset = symbol != null ? config.getAsset(symbol) : null;
                if (asset == null || price <= 0 || qty <= 0) {
                    player.sendMessage("§c[BlockStreet] Please fill in all fields before submitting.");
                    return;
                }

                // Reset form state for next use
                session.setBuyFormSymbol(null);
                session.setBuyFormPrice(0.0);
                session.setBuyFormQty(0);
                session.setBuyFormDuration(0);

                player.closeInventory();
                guiManager.getOrderSubmissionService().submitOrder(
                        player, symbol, OrderSide.BUY, OrderType.LIMIT, price, qty);
            }

            default -> { /* ignore other slots */ }
        }
    }

    // ── Item picker sub-panel ────────────────────────────────────────────────────

    /**
     * Opens a simple 27-slot inventory listing all tradeable assets so the player
     * can pick which item to create a Buy Order for.
     */
    private void openItemPicker(GuiSession session, Player player) {
        var assets = new java.util.ArrayList<>(config.getAllAssets().values());
        int rows = Math.max(1, (int) Math.ceil(assets.size() / 9.0)) + 1;
        rows = Math.min(rows, 6);  // cap at 6 rows (54 slots)

        org.bukkit.inventory.Inventory picker =
                org.bukkit.Bukkit.createInventory(null, rows * 9, "§8Pick an item:");

        for (int i = 0; i < Math.min(assets.size(), rows * 9); i++) {
            AssetConfig a = assets.get(i);
            ItemStack slot = new ItemStack(a.getMaterial());
            ItemMeta m = slot.getItemMeta();
            if (m != null) {
                m.setDisplayName("§f" + a.getDisplayName());
                m.setLore(List.of(
                        "§7Symbol: §f" + a.getSymbol(),
                        "§eClick to select."
                ));
                slot.setItemMeta(m);
            }
            picker.setItem(i, slot);
        }

        // Store picker reference in session so GuiListener can route clicks correctly
        session.setItemPickerOpen(true);
        session.setInventory(picker);
        player.openInventory(picker);
    }

    /**
     * Handles a click inside the item-picker sub-panel.
     * Called by GuiManager when {@code session.isItemPickerOpen() == true}.
     */
    public void onItemPickerClick(InventoryClickEvent event, GuiSession session) {
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(session.getInventory())) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Find which asset corresponds to this material
        for (AssetConfig a : config.getAllAssets().values()) {
            if (a.getMaterial() == clicked.getType() && a.isEnabled()) {
                session.setBuyFormSymbol(a.getSymbol());
                break;
            }
        }

        session.setItemPickerOpen(false);
        guiManager.openGui(session.getPlayer(), com.perseusj.blockstreet.gui.GuiTab.BUY_ORDER);
    }
}
