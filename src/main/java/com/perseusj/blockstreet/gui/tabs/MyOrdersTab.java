package com.perseusj.blockstreet.gui.tabs;

import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.engine.MatchingEngine;
import com.perseusj.blockstreet.engine.OrderSubmissionService;
import com.perseusj.blockstreet.engine.book.OrderBook;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.gui.GuiSession;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MyOrdersTab implements GuiTabHandler {

    private final MatchingEngine engine;
    private final OrderSubmissionService orderSubmissionService;
    private final ConfigManager config;

    public MyOrdersTab(MatchingEngine engine, OrderSubmissionService orderSubmissionService, ConfigManager config) {
        this.engine = engine;
        this.orderSubmissionService = orderSubmissionService;
        this.config = config;
    }

    @Override
    public void render(GuiSession session) {
        Inventory inv = session.getInventory();
        UUID playerId = session.getPlayer().getUniqueId();
        
        List<Order> activeOrders = new ArrayList<>();
        for (OrderBook book : engine.getAllBooks().values()) {
            for (Order o : book.getAllOrders()) {
                if (o.getPlayerId().equals(playerId)) {
                    activeOrders.add(o);
                }
            }
        }

        for (int i = 0; i < Math.min(45, activeOrders.size()); i++) {
            Order o = activeOrders.get(i);
            Material mat = config.getAsset(o.getSymbol()).getMaterial();
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§eOrder: " + o.getSide() + " " + o.getSymbol());
                meta.setLore(List.of(
                    "§7Price: " + config.getCurrencySymbol() + o.getLimitPrice(),
                    "§7Remaining Qty: " + o.getQuantityRemaining() + " / " + o.getQuantityOriginal(),
                    "§cRight-click to cancel"
                ));
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }
    }

    @Override
    public void onClick(InventoryClickEvent event, GuiSession session) {
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(session.getInventory())) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 45) return;

        if (event.isRightClick()) {
            UUID playerId = session.getPlayer().getUniqueId();
            List<Order> activeOrders = new ArrayList<>();
            for (OrderBook book : engine.getAllBooks().values()) {
                for (Order o : book.getAllOrders()) {
                    if (o.getPlayerId().equals(playerId)) {
                        activeOrders.add(o);
                    }
                }
            }

            if (slot < activeOrders.size()) {
                Order target = activeOrders.get(slot);
                boolean cancelled = orderSubmissionService.cancelOrder(target);
                if (cancelled) {
                    session.getPlayer().sendMessage("§e[BlockStreet] Order cancelled.");
                    render(session);
                    session.getPlayer().updateInventory();
                }
            }
        }
    }
}
