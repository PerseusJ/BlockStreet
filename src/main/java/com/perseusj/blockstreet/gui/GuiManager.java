package com.perseusj.blockstreet.gui;

import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.engine.MatchingEngine;
import com.perseusj.blockstreet.engine.OrderSubmissionService;
import com.perseusj.blockstreet.gui.tabs.GuiTabHandler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class GuiManager {

    private final Plugin plugin;
    private final MatchingEngine engine;
    private final OrderSubmissionService orderSubmissionService;
    private final ConfigManager config;
    private final Logger logger;

    private final Map<UUID, GuiSession> openSessions = new ConcurrentHashMap<>();
    private final Map<GuiTab, GuiTabHandler> tabHandlers = new EnumMap<>(GuiTab.class);

    public GuiManager(Plugin plugin, MatchingEngine engine,
                      OrderSubmissionService orderSubmissionService,
                      ConfigManager config) {
        this.plugin = plugin;
        this.engine = engine;
        this.orderSubmissionService = orderSubmissionService;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    public void registerTabHandler(GuiTab tab, GuiTabHandler handler) {
        tabHandlers.put(tab, handler);
    }

    public void start() {
        int refreshTicks = config.getGuiRefreshTicks();
        new BukkitRunnable() {
            @Override
            public void run() {
                refreshAllGuis();
            }
        }.runTaskTimer(plugin, refreshTicks, refreshTicks);


    }

    public void stop() {
        // Not specifically tracking tasks here, just letting the plugin disable stop them.
    }

    /** Opens the GUI at the default (BROWSE) tab. */
    public void openGui(Player player) {
        openGui(player, GuiTab.BROWSE);
    }

    /**
     * Opens the GUI at the specified starting tab.
     * Called by {@link com.perseusj.blockstreet.commands.subcommands.MarketOrderCommand}
     * when processing {@code /bs market open <tab>} — e.g. from the Market Ledger book buttons.
     *
     * @param player   the player to open the GUI for
     * @param startTab the tab to display first (e.g. {@link GuiTab#BROWSE} or {@link GuiTab#MAILBOX})
     */
    public void openGui(Player player, GuiTab startTab) {
        GuiSession session = new GuiSession(player);
        session.setActiveTab(startTab != null ? startTab : GuiTab.BROWSE);
        Inventory inv = Bukkit.createInventory(null, 54, buildInventoryTitle(session));
        session.setInventory(inv);
        openSessions.put(player.getUniqueId(), session);

        renderSession(session);
        player.openInventory(inv);
    }

    /** Builds the inventory title based on session state (vanilla vs resource-pack mode). */
    private String buildInventoryTitle(GuiSession session) {
        if (session.isResourcePackAccepted()) {
            // RP mode: custom bitmap char + negative-space offset for pixel-perfect overlay
            return "\uF800\uF800\uF800\uE000";
        }
        // Vanilla fallback
        return ChatColor.DARK_GRAY + "❙ " + ChatColor.GOLD + "BlockStreet Market";
    }


    public void renderSession(GuiSession session) {
        if (!session.getPlayer().isOnline()) return;

        Inventory inv = session.getInventory();
        inv.clear(); // Clear all slots

        // Delegate content rendering to tab handler
        GuiTabHandler handler = tabHandlers.get(session.getActiveTab());
        if (handler != null) {
            handler.render(session);
        }

        // Render bottom row tabs (45-53)
        renderBottomTabs(session, inv);
    }

    private void renderBottomTabs(GuiSession session, Inventory inv) {
        GuiTab active = session.getActiveTab();
        
        inv.setItem(45, createTabItem(Material.COMPASS, "Browse", active == GuiTab.BROWSE));
        inv.setItem(47, createTabItem(Material.GOLD_INGOT, "Sell Items", active == GuiTab.SELL));
        inv.setItem(49, createTabItem(Material.EMERALD, "Create Buy Order", active == GuiTab.BUY_ORDER));
        inv.setItem(51, createTabItem(Material.PAPER, "My Orders", active == GuiTab.MY_ORDERS));
        inv.setItem(53, createTabItem(Material.CHEST, "Mailbox", active == GuiTab.MAILBOX));
    }

    private ItemStack createTabItem(Material mat, String name, boolean active) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (active) {
                meta.setDisplayName("§a§l" + name);
                // Optionally add enchantment glow here
            } else {
                meta.setDisplayName("§7" + name);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public GuiSession getSession(UUID playerId) {
        return openSessions.get(playerId);
    }

    public boolean isBlockStreetInventory(Inventory inv) {
        for (GuiSession session : openSessions.values()) {
            if (session.getInventory().equals(inv)) return true;
        }
        return false;
    }

    public void handleClick(InventoryClickEvent event, GuiSession session) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot >= 45 && slot <= 53) {
            GuiTab newTab = null;
            if (slot == 45) newTab = GuiTab.BROWSE;
            else if (slot == 47) newTab = GuiTab.SELL;
            else if (slot == 49) newTab = GuiTab.BUY_ORDER;
            else if (slot == 51) newTab = GuiTab.MY_ORDERS;
            else if (slot == 53) newTab = GuiTab.MAILBOX;

            if (newTab != null && newTab != session.getActiveTab()) {
                session.setActiveTab(newTab);
                session.setBrowsePage(0); // reset page
                renderSession(session);
                player.updateInventory();
            }
            return;
        }

        // Delegate to active tab handler
        GuiTabHandler handler = tabHandlers.get(session.getActiveTab());
        if (handler != null) {
            handler.onClick(event, session);
        }
    }

    public void handleClose(InventoryCloseEvent event, GuiSession session) {
        if (!session.isAwaitingChatInput()) {
            openSessions.remove(session.getPlayer().getUniqueId());
        }
    }

    public void handlePlayerQuit(UUID playerId) {
        openSessions.remove(playerId);
    }

    private void refreshAllGuis() {
        for (GuiSession session : openSessions.values()) {
            if (session.isAwaitingChatInput()) continue;
            renderSession(session);
        }
    }


    
    public MatchingEngine getEngine() { return engine; }
    public OrderSubmissionService getOrderSubmissionService() { return orderSubmissionService; }
    public ConfigManager getConfig() { return config; }
}
