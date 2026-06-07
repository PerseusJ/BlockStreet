package com.perseusj.blockstreet.gui;

import org.bukkit.inventory.Inventory;

import java.util.UUID;

/**
 * Immutable snapshot of an open BlockStreet GUI session for a single player.
 *
 * <p>Stored in {@link GuiManager#openSessions} so that
 * {@code InventoryClickEvent} and {@code InventoryCloseEvent} handlers can
 * quickly determine whether an event belongs to a BlockStreet GUI, which
 * type it is, and what asset it is showing.
 *
 * @param playerId  UUID of the player who has the GUI open
 * @param type      which BlockStreet GUI type this session represents
 * @param inventory the Bukkit {@link Inventory} object backing this GUI
 * @param symbol    the asset symbol being displayed (e.g. "DIAMOND"); may be
 *                  {@code null} for GUI types that are not asset-specific
 */
public record GuiSession(
        UUID      playerId,
        GuiType   type,
        Inventory inventory,
        String    symbol
) {}
