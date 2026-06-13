package com.perseusj.blockstreet.gui.tabs;

import com.perseusj.blockstreet.gui.GuiSession;
import org.bukkit.event.inventory.InventoryClickEvent;

public interface GuiTabHandler {
    
    /**
     * Renders the tab content into slots 0-44 of the session's inventory.
     * The bottom row (slots 45-53) is reserved for tab navigation and is handled by GuiManager.
     *
     * @param session The current GUI session.
     */
    void render(GuiSession session);

    /**
     * Handles a click in the GUI. Clicks on the bottom row (slots 45-53) are handled by GuiManager.
     *
     * @param event The click event.
     * @param session The current GUI session.
     */
    void onClick(InventoryClickEvent event, GuiSession session);
}
