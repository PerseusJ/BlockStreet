package com.perseusj.blockstreet.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatInputManager implements Listener {
    private final Map<UUID, ChatInputContext> pendingInputs = new ConcurrentHashMap<>();
    private final Plugin plugin;

    public ChatInputManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void awaitInput(Player player, ChatInputContext ctx) {
        pendingInputs.put(player.getUniqueId(), ctx);
        player.sendMessage(ctx.prompt());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ChatInputContext ctx = pendingInputs.remove(uuid);
        if (ctx == null) return;
        event.setCancelled(true);
        String raw = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                ctx.resolve(raw, event.getPlayer());
            } catch (NumberFormatException e) {
                // Invalid numeric input — inform player and re-prompt (do not crash)
                event.getPlayer().sendMessage(
                    "§c[BlockStreet] Invalid input '" + raw + "' — please enter a number.");
                // Re-queue the same context so they get another attempt
                awaitInput(event.getPlayer(), ctx);
            }
        });
    }

    /**
     * Cleans up any pending input context when a player disconnects.
     * Without this, the pendingInputs map leaks one entry per disconnected player
     * who was mid-input, growing unbounded over time.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingInputs.remove(event.getPlayer().getUniqueId());
    }
}
