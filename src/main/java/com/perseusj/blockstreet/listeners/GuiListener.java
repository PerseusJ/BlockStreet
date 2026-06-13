package com.perseusj.blockstreet.listeners;

import com.perseusj.blockstreet.gui.GuiManager;
import com.perseusj.blockstreet.gui.GuiSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class GuiListener implements Listener {

    private final GuiManager guiManager;
    private final Plugin plugin;

    public GuiListener(GuiManager guiManager, Plugin plugin) {
        this.guiManager = guiManager;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        GuiSession session = guiManager.getSession(player.getUniqueId());
        if (session == null) return;
        
        if (event.getView().getTopInventory().equals(session.getInventory())) {
            guiManager.handleClick(event, session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        GuiSession session = guiManager.getSession(player.getUniqueId());
        if (session == null) return;
        if (!session.getInventory().equals(event.getInventory())) return;

        guiManager.handleClose(event, session);
    }



    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        guiManager.handlePlayerQuit(event.getPlayer().getUniqueId());
    }
}
