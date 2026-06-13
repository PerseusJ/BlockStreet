package com.perseusj.blockstreet;

import com.perseusj.blockstreet.commands.BlockStreetCommand;
import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.db.DatabaseService;
import com.perseusj.blockstreet.db.DbWriteQueue;
import com.perseusj.blockstreet.db.UpsertOrderTask;
import com.perseusj.blockstreet.db.MailboxLedgerService;
import com.perseusj.blockstreet.db.PlayerCacheDao;
import com.perseusj.blockstreet.engine.ExpirationScheduler;
import com.perseusj.blockstreet.engine.MatchingEngine;
import com.perseusj.blockstreet.engine.OrderSubmissionService;
import com.perseusj.blockstreet.engine.SettlementDispatcher;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.gui.GuiTab;
import com.perseusj.blockstreet.gui.tabs.BrowseTab;
import com.perseusj.blockstreet.gui.tabs.BuyOrderTab;
import com.perseusj.blockstreet.gui.tabs.MailboxTab;
import com.perseusj.blockstreet.gui.tabs.MyOrdersTab;
import com.perseusj.blockstreet.gui.tabs.SellTab;
import com.perseusj.blockstreet.gui.GuiManager;
import com.perseusj.blockstreet.gui.ChatInputManager;
import com.perseusj.blockstreet.listeners.GuiListener;
import com.perseusj.blockstreet.listeners.PlayerListener;
import com.perseusj.blockstreet.managers.VaultEconomyService;
import com.perseusj.blockstreet.utils.ItemFactory;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;

/**
 * Main plugin class — entry point for enable and disable lifecycle.
 *
 * <h2>Startup Sequence</h2>
 * <ol>
 *   <li>Save default config.yml if absent.</li>
 *   <li>Load {@link ConfigManager} — parses assets, engine settings.</li>
 *   <li>Resolve Vault {@link Economy} via service provider.</li>
 *   <li>Initialise singletons: {@link MailboxManager}, {@link VaultEconomyService}.</li>
 *   <li>Register {@link ItemFactory} asset mappings.</li>
 *   <li>Construct {@link SettlementDispatcher} and wire it into {@link MatchingEngine}.</li>
 *   <li>Start the matching engine thread.</li>
 *   <li>Register Bukkit event listeners.</li>
 * </ol>
 *
 * <h2>Shutdown Sequence</h2>
 * <ol>
 *   <li>Stop the matching engine (poison-pill + join up to 5s).</li>
 *   <li>Future Phase 4: flush DB write queue before connection close.</li>
 * </ol>
 */
public class BlockStreet extends JavaPlugin {

    // ──────────────────────────── Singleton services ──────────────────────────────

    private ConfigManager        configManager;
    private VaultEconomyService  vaultEconomy;
    private DatabaseService      databaseService;
    private DbWriteQueue         dbWriteQueue;
    private MailboxLedgerService mailboxLedgerService;
    private PlayerCacheDao       playerCacheDao;
    private ExpirationScheduler  expirationScheduler;
    private MatchingEngine       matchingEngine;
    private SettlementDispatcher settlementDispatcher;
    private OrderSubmissionService orderSubmissionService;
    private GuiManager           guiManager;
    private ChatInputManager     chatInputManager;

    // ──────────────────────────── Static accessor ─────────────────────────────────

    private static BlockStreet INSTANCE;

    /** Returns the plugin instance for use by command handlers and other static singletons. */
    public static BlockStreet getInstance() { return INSTANCE; }

    // ──────────────────────────── Lifecycle ──────────────────────────────────────

    @Override
    public void onEnable() {
        INSTANCE = this;

        // ── 1. Config ──────────────────────────────────────────────────────────
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        configManager.load();

        // ── 2. Vault ──────────────────────────────────────────────────────────
        Economy economy = resolveVault();
        if (economy == null) {
            getLogger().severe("[BlockStreet] Vault economy provider not found. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        vaultEconomy = new VaultEconomyService(economy, getLogger());

        // ── 3. Database & Queues ─────────────────────────────────────────────────
        databaseService = new DatabaseService(getDataFolder(), getLogger());
        databaseService.init();
        try {
            databaseService.initSchema();
        } catch (SQLException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "[BlockStreet] Failed to initialize database schema: {0}", e.getMessage());
            getLogger().severe("[BlockStreet] Disabling plugin to prevent data corruption.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        dbWriteQueue = new DbWriteQueue(databaseService, getLogger());
        dbWriteQueue.start();

        // ── 4. Mailbox Ledger & Player Cache ───────────────────────────────────
        mailboxLedgerService = new MailboxLedgerService(this, databaseService, economy, dbWriteQueue, getLogger());
        playerCacheDao = new PlayerCacheDao(databaseService, getLogger());

        // ── 5. ItemFactory registry ────────────────────────────────────────────
        ItemFactory.clearRegistry();
        for (AssetConfig asset : configManager.getAllAssets().values()) {
            ItemFactory.register(asset.getSymbol(), asset.getMaterial());
        }
        getLogger().log(java.util.logging.Level.INFO, "[BlockStreet] Registered {0} asset(s) in ItemFactory.", configManager.getAllAssets().size());

        // ── 6. Settlement Dispatcher ────────────────────────────────────────────
        settlementDispatcher = new SettlementDispatcher(this, mailboxLedgerService, configManager);

        // ── 7. Matching Engine ─────────────────────────────────────────────────
        matchingEngine = new MatchingEngine(getLogger(), settlementDispatcher::dispatch);
        matchingEngine.setDbWriteQueue(dbWriteQueue);
        matchingEngine.start();

        // ── 8. Reboot Recovery ──────────────────────────────────────────
        // Restore resting orders from DB asynchronously (non-blocking main thread)
        final MatchingEngine engineRef = matchingEngine;
        final ConfigManager configRef  = configManager;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<Order> resting = databaseService.loadOpenOrders(configRef);
            for (Order order : resting) {
                engineRef.submitOrder(order);
            }
        });

        // ── 9. Order Submission Service ─────────────────────────────────────────
        orderSubmissionService = new OrderSubmissionService(
                matchingEngine, vaultEconomy, configManager, mailboxLedgerService, getLogger());

        // ── 10. Expiration Scheduler ────────────────────────────────────────────
        long sweepTicks = configManager.getExpirationSweepTicks();
        expirationScheduler = new ExpirationScheduler(this, databaseService, configManager, matchingEngine, mailboxLedgerService, getLogger());
        expirationScheduler.runTaskTimer(this, sweepTicks, sweepTicks);

        // ── 12. GUI Manager ────────────────────────────────────────────────────────────
        guiManager = new GuiManager(this, matchingEngine, orderSubmissionService, configManager);
        guiManager.registerTabHandler(GuiTab.BROWSE, new BrowseTab(configManager, matchingEngine));
        guiManager.registerTabHandler(GuiTab.SELL, new SellTab(configManager, guiManager));
        guiManager.registerTabHandler(GuiTab.BUY_ORDER, new BuyOrderTab(configManager, guiManager));
        guiManager.registerTabHandler(GuiTab.MY_ORDERS, new MyOrdersTab(matchingEngine, orderSubmissionService, configManager));
        guiManager.registerTabHandler(GuiTab.MAILBOX, new MailboxTab(mailboxLedgerService, this));
        guiManager.start();

        // ── 12. Event Listeners ─────────────────────────────────────────────────
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(guiManager, this), this);
        chatInputManager = new ChatInputManager(this);
        getServer().getPluginManager().registerEvents(chatInputManager, this);

        // ── 13. Command Executor ────────────────────────────────────────────────
        BlockStreetCommand commandHandler = new BlockStreetCommand(this);
        PluginCommand bsCommand = getCommand("blockstreet");
        if (bsCommand != null) {
            bsCommand.setExecutor(commandHandler);
            bsCommand.setTabCompleter(commandHandler);
        } else {
            getLogger().severe("[BlockStreet] Could not find 'blockstreet' command in plugin.yml!");
        }

        getLogger().info("[BlockStreet] Phase 5 online — commands, config & edge-case safeguards active.");
    }

    @Override
    public void onDisable() {
        // 1. Stop GUI refresh loop and Expiration Scheduler
        if (guiManager != null) {
            guiManager.stop();
        }
        if (expirationScheduler != null) {
            expirationScheduler.cancel();
        }

        // 2. Stop matching engine gracefully (poison-pill + 5s join)
        if (matchingEngine != null && matchingEngine.isRunning()) {
            matchingEngine.stop();
        }

        // 3. Persist all currently resting orders to DB before shutdown
        if (dbWriteQueue != null && matchingEngine != null) {
            matchingEngine.getAllBooks().values().forEach(book ->
                book.getAllOrders().forEach(order ->
                    dbWriteQueue.offer(new UpsertOrderTask(order))
                )
            );
        }

        // 4. Flush all pending DB writes and close connection pool
        if (dbWriteQueue != null) {
            dbWriteQueue.shutdown();
        }
        if (databaseService != null) {
            databaseService.close();
        }

        getLogger().info("[BlockStreet] has been disabled.");
    }

    // ──────────────────────────── Vault resolution ───────────────────────────────

    /**
     * Attempts to resolve a Vault {@link Economy} provider.
     *
     * @return the Economy instance, or {@code null} if not found
     */
    private Economy resolveVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("[BlockStreet] Vault plugin not found on the server.");
            return null;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("[BlockStreet] No Vault Economy implementation registered " +
                    "(is an economy plugin like EssentialsX installed?)");
            return null;
        }
        return rsp.getProvider();
    }

    // ──────────────────────────── Service accessors ───────────────────────────────

    /** Returns the config manager (safe to call after onEnable completes). */
    public ConfigManager getConfigManager()              { return configManager; }

    /** Returns the Vault economy wrapper. */
    public VaultEconomyService getVaultEconomy()         { return vaultEconomy; }

    /** Returns the matching engine. */
    public MatchingEngine getMatchingEngine()            { return matchingEngine; }

    /** Returns the settlement dispatcher. */
    public SettlementDispatcher getSettlementDispatcher() { return settlementDispatcher; }

    /** Returns the order submission service. */
    public OrderSubmissionService getOrderSubmissionService() { return orderSubmissionService; }

    /** Returns the GUI manager. */
    public GuiManager getGuiManager()                   { return guiManager; }

    /** Returns the database service (Phase 4). */
    public DatabaseService getDatabaseService()          { return databaseService; }

    /** Returns the Mailbox Ledger Service. */
    public MailboxLedgerService getMailboxLedgerService() { return mailboxLedgerService; }

    /** Returns the Player Cache DAO. */
    public PlayerCacheDao getPlayerCacheDao() { return playerCacheDao; }

    /** Returns the async DB write queue (Phase 4). */
    public DbWriteQueue getDbWriteQueue() { return dbWriteQueue; }

    /** Returns the Chat Input Manager. */
    public ChatInputManager getChatInputManager() { return chatInputManager; }
}