package com.perseusj.blockstreet.engine;

import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.db.DatabaseService;
import com.perseusj.blockstreet.db.MailboxLedgerService;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderStatus;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.logging.Logger;

/**
 * Periodically sweeps the database for expired resting orders and dispatches
 * them to the MatchingEngine for cancellation.
 */
public final class ExpirationScheduler extends BukkitRunnable {

    private final Plugin plugin;
    private final DatabaseService db;
    private final ConfigManager config;
    private final MatchingEngine engine;
    private final MailboxLedgerService mailbox;
    private final Logger logger;

    public ExpirationScheduler(Plugin plugin, DatabaseService db, ConfigManager config, MatchingEngine engine, MailboxLedgerService mailbox, Logger logger) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
        this.engine = engine;
        this.mailbox = mailbox;
        this.logger = logger;
    }

    @Override
    public void run() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            List<Order> expired = db.loadExpiredOrders(now, config);
            
            if (!expired.isEmpty()) {
                logger.info("[BlockStreet] Expiration sweep found " + expired.size() + " expired order(s).");
            }
            
            for (Order order : expired) {
                // Mark as EXPIRED and route to engine so it cleanly un-rests from the book
                order.setStatus(OrderStatus.EXPIRED);
                engine.submitOrder(order);
            }
        });
    }
}
