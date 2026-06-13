package com.perseusj.blockstreet.gui.tabs;

import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.engine.MatchingEngine;
import com.perseusj.blockstreet.engine.book.MarketDepthSnapshot;
import com.perseusj.blockstreet.engine.book.OrderBook;
import com.perseusj.blockstreet.gui.GuiSession;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class BrowseTab implements GuiTabHandler {

    private final ConfigManager config;
    private final MatchingEngine engine;

    public BrowseTab(ConfigManager config, MatchingEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    @Override
    public void render(GuiSession session) {
        Inventory inv = session.getInventory();
        String cat = session.getBrowseCategory();
        
        if (cat == null) {
            // Render categories / assets list
            renderAssetList(session, inv);
        } else {
            // Render specific asset depth
            renderAssetDepth(session, inv, cat);
        }
    }

    private void renderAssetList(GuiSession session, Inventory inv) {
        List<AssetConfig> assets = new ArrayList<>(config.getAllAssets().values());
        
        int pageSize = 45;
        int maxPages = (int) Math.ceil((double) assets.size() / pageSize);
        int page = session.getBrowsePage();
        if (page < 0) page = 0;
        if (page >= maxPages && maxPages > 0) page = maxPages - 1;
        
        int start = page * pageSize;
        int end = Math.min(start + pageSize, assets.size());
        
        for (int i = 0; i < (end - start); i++) {
            AssetConfig asset = assets.get(start + i);
            ItemStack item = new ItemStack(asset.getMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§a" + asset.getDisplayName());
                List<String> lore = new ArrayList<>();
                
                OrderBook book = engine.getBook(asset.getSymbol());
                if (book != null) {
                    MarketDepthSnapshot depth = book.getDepthSnapshot();
                    double lowestAsk = depth.asks().isEmpty() ? 0 : depth.asks().get(0).price();
                    double highestBid = depth.bids().isEmpty() ? 0 : depth.bids().get(0).price();
                    
                    lore.add("§7Lowest Ask: " + (lowestAsk > 0 ? config.getCurrencySymbol() + lowestAsk : "N/A"));
                    lore.add("§7Highest Bid: " + (highestBid > 0 ? config.getCurrencySymbol() + highestBid : "N/A"));
                } else {
                    lore.add("§7Loading data...");
                }
                
                lore.add("");
                lore.add("§eClick to view details");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }
        
        // Next/Prev buttons if needed can be placed at row 4 (slots 36-44) or handled specially
    }

    private void renderAssetDepth(GuiSession session, Inventory inv, String symbol) {
        AssetConfig asset = config.getAsset(symbol);
        if (asset == null) return;

        ItemStack backButton = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backButton.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName("§cBack to Assets");
            backButton.setItemMeta(backMeta);
        }
        inv.setItem(0, backButton);

        OrderBook book = engine.getBook(symbol);
        if (book != null) {
            MarketDepthSnapshot depth = book.getDepthSnapshot();
            
            // Asks (Sells) on the right
            for (int i = 0; i < Math.min(5, depth.asks().size()); i++) {
                MarketDepthSnapshot.DepthLevel level = depth.asks().get(i);
                ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§cAsk: " + config.getCurrencySymbol() + level.price());
                    meta.setLore(List.of("§7Quantity: " + level.totalVolume()));
                    item.setItemMeta(meta);
                }
                inv.setItem(16 - i, item); // slots 12-16
            }

            // Bids (Buys) on the left
            for (int i = 0; i < Math.min(5, depth.bids().size()); i++) {
                MarketDepthSnapshot.DepthLevel level = depth.bids().get(i);
                ItemStack item = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§aBid: " + config.getCurrencySymbol() + level.price());
                    meta.setLore(List.of("§7Quantity: " + level.totalVolume()));
                    item.setItemMeta(meta);
                }
                inv.setItem(10 + i, item); // slots 10-14
            }
        }
    }

    @Override
    public void onClick(InventoryClickEvent event, GuiSession session) {
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(session.getInventory())) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 45) return;

        if (session.getBrowseCategory() == null) {
            // Asset list view
            List<AssetConfig> assets = new ArrayList<>(config.getAllAssets().values());
            int pageSize = 45;
            int page = session.getBrowsePage();
            int index = (page * pageSize) + slot;
            
            if (index >= 0 && index < assets.size()) {
                AssetConfig clicked = assets.get(index);
                session.setBrowseCategory(clicked.getSymbol());
                // GuiManager handles re-rendering, but let's signal re-render by calling render here?
                // Actually, the easiest way is to just call render manually or let GuiManager do it.
                // We'll just call render(session) and player.updateInventory()
                render(session);
                session.getPlayer().updateInventory();
            }
        } else {
            // Depth view
            if (slot == 0) {
                // Back button
                session.setBrowseCategory(null);
                render(session);
                session.getPlayer().updateInventory();
            }
        }
    }
}
