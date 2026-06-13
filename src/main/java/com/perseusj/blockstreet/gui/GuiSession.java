package com.perseusj.blockstreet.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.List;

public final class GuiSession {
    private final Player player;
    private Inventory inventory;
    private GuiTab activeTab = GuiTab.BROWSE;
    
    private int browsePage = 0;
    private String browseCategory = null; // null = all
    
    private boolean awaitingChatInput = false;
    private ChatInputContext chatInputContext;
    
    private boolean resourcePackAccepted = false;

    // ── Sub-panel state (SellTab 27-slot overlay) ────────────────────────────
    /** True while the SellTab quick-sell/create-order sub-panel is open. */
    private boolean subPanelOpen   = false;
    /** Symbol of the asset the player selected in the sub-panel. */
    private String  subPanelAsset  = null;
    /** The original ItemStack the player clicked to open the sub-panel. */
    private ItemStack subPanelItem = null;

    public GuiSession(Player player) {
        this.player = player;
    }

    public Player getPlayer() { return player; }
    
    public Inventory getInventory() { return inventory; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    public GuiTab getActiveTab() { return activeTab; }
    public void setActiveTab(GuiTab activeTab) { this.activeTab = activeTab; }

    public int getBrowsePage() { return browsePage; }
    public void setBrowsePage(int browsePage) { this.browsePage = browsePage; }

    public String getBrowseCategory() { return browseCategory; }
    public void setBrowseCategory(String browseCategory) { this.browseCategory = browseCategory; }

    public boolean isAwaitingChatInput() { return awaitingChatInput; }
    public void setAwaitingChatInput(boolean awaitingChatInput) { this.awaitingChatInput = awaitingChatInput; }

    public ChatInputContext getChatInputContext() { return chatInputContext; }
    public void setChatInputContext(ChatInputContext chatInputContext) { this.chatInputContext = chatInputContext; }

    public boolean isResourcePackAccepted() { return resourcePackAccepted; }
    public void setResourcePackAccepted(boolean resourcePackAccepted) { this.resourcePackAccepted = resourcePackAccepted; }

    // ── Sub-panel accessors ──────────────────────────────────────────────────
    public boolean isSubPanelOpen()                { return subPanelOpen; }
    public void    setSubPanelOpen(boolean v)      { this.subPanelOpen = v; }

    public String  getSubPanelAsset()              { return subPanelAsset; }
    public void    setSubPanelAsset(String v)      { this.subPanelAsset = v; }

    public ItemStack getSubPanelItem()             { return subPanelItem; }
    public void      setSubPanelItem(ItemStack v)  { this.subPanelItem = v; }

    // ── Buy Order form state (BuyOrderTab) ──────────────────────────────────
    /** Symbol selected in the Buy Order form (null = none chosen yet). */
    private String  buyFormSymbol   = null;
    /** Price per unit entered in the Buy Order form (0.0 = not yet entered). */
    private double  buyFormPrice    = 0.0;
    /** Quantity entered in the Buy Order form (0 = not yet entered). */
    private int     buyFormQty      = 0;
    /** Duration in days for the Buy Order form (0 = use default from config). */
    private int     buyFormDuration = 0;
    /** True while the BuyOrderTab item-picker sub-panel is open. */
    private boolean itemPickerOpen  = false;

    public String  getBuyFormSymbol()              { return buyFormSymbol; }
    public void    setBuyFormSymbol(String v)      { this.buyFormSymbol = v; }

    public double  getBuyFormPrice()               { return buyFormPrice; }
    public void    setBuyFormPrice(double v)       { this.buyFormPrice = v; }

    public int     getBuyFormQty()                 { return buyFormQty; }
    public void    setBuyFormQty(int v)            { this.buyFormQty = v; }

    public int     getBuyFormDuration()            { return buyFormDuration; }
    public void    setBuyFormDuration(int v)       { this.buyFormDuration = v; }

    public boolean isItemPickerOpen()              { return itemPickerOpen; }
    public void    setItemPickerOpen(boolean v)    { this.itemPickerOpen = v; }

    // ── Mailbox entry cache (MailboxTab) ─────────────────────────────────────
    /**
     * Cached list of unclaimed mailbox entries for the current GUI session.
     * {@code null} = not yet loaded (triggers an async load on next render).
     * Set to an empty list while a load is in-flight to prevent duplicate queries.
     */
    private List<com.perseusj.blockstreet.db.MailboxLedgerEntry> mailboxEntries = null;

    public List<com.perseusj.blockstreet.db.MailboxLedgerEntry> getMailboxEntries() {
        return mailboxEntries;
    }
    public void setMailboxEntries(
            List<com.perseusj.blockstreet.db.MailboxLedgerEntry> entries) {
        this.mailboxEntries = entries;
    }
}


