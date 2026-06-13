package com.perseusj.blockstreet.engine;

import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.db.MailboxLedgerService;
import com.perseusj.blockstreet.engine.book.MarketDepthSnapshot;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderStatus;
import com.perseusj.blockstreet.engine.model.OrderType;
import com.perseusj.blockstreet.managers.VaultEconomyService;
import com.perseusj.blockstreet.utils.ItemFactory;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Executes Phase 2A of the two-phase commit protocol for every order submission.
 *
 * <h2>Phase 2A — Pre-Submission Asset Lock (Main Thread)</h2>
 * <ol>
 *   <li>Validate order parameters via {@link OrderValidator}.</li>
 *   <li>Normalise limit price to the asset's {@code price-tick}.</li>
 *   <li>Reserve assets atomically (Vault withdraw for BUY; item removal for SELL).</li>
 *   <li>Set {@link Order#tryLockAssets()} to {@code true}.</li>
 *   <li>Offer the order to the {@link MatchingEngine}'s submission queue.</li>
 * </ol>
 *
 * <h2>Duplicate Submission Guard</h2>
 * A per-player {@link AtomicBoolean} {@code submitting} flag prevents a player from
 * having two concurrent Phase 2A operations in flight (e.g. double-click on GUI button).
 * The flag is cleared immediately after the order enters the queue.
 *
 * <h2>Thread Safety</h2>
 * All methods in this class <strong>must</strong> be called from the
 * <strong>Server Main Thread</strong> — Vault, inventory, and player state APIs require it.
 */
public final class OrderSubmissionService {

    private final MatchingEngine       engine;
    private final VaultEconomyService  economy;
    private final ConfigManager        config;
    private final MailboxLedgerService mailboxLedger;
    private final Logger               logger;

    /**
     * Per-player guard preventing concurrent Phase 2A operations.
     * Key = player UUID; value = {@code true} while an order is being locked.
     */
    private final Map<UUID, AtomicBoolean> submittingGuard = new ConcurrentHashMap<>();

    /**
     * Tracks the order currently in the Phase 2A asset-lock window for each player.
     * Populated before {@link #lockAssets} is called; removed immediately after
     * {@link MatchingEngine#submitOrder} succeeds.
     *
     * <p>Edge case E19: if a player disconnects while this map contains their UUID,
     * {@link #rollbackLock} is called by {@code PlayerQuitEvent} to refund the locked assets.
     */
    private final Map<UUID, Order> pendingLockOrders = new ConcurrentHashMap<>();

    // ──────────────────────────── Constructor ────────────────────────────────────

    public OrderSubmissionService(MatchingEngine engine,
                                  VaultEconomyService economy,
                                  ConfigManager config,
                                  MailboxLedgerService mailboxLedger,
                                  Logger logger) {
        this.engine        = engine;
        this.economy       = economy;
        this.config        = config;
        this.mailboxLedger = mailboxLedger;
        this.logger        = logger;
    }

    // ──────────────────────────── Public submission API ──────────────────────────

    /**
     * Executes Phase 2A and, if successful, submits the order to the engine.
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param player     the player placing the order
     * @param symbol     asset symbol (e.g. "DIAMOND")
     * @param side       BUY or SELL
     * @param type       LIMIT or MARKET
     * @param rawPrice   the raw price entered by the player (0 for MARKET)
     * @param quantity   the requested quantity
     * @return the submitted {@link Order}, or {@code null} if validation or asset lock failed
     *         (the player will have been notified of the reason via chat)
     */
    public Order submitOrder(Player player, String symbol, OrderSide side,
                             OrderType type, double rawPrice, int quantity) {

        // ── Duplicate-submission guard ─────────────────────────────────────────
        AtomicBoolean guard = submittingGuard.computeIfAbsent(
                player.getUniqueId(), id -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            player.sendMessage("§c[BlockStreet] Please wait — your previous order is still being processed.");
            return null;
        }

        try {
            return doSubmit(player, symbol, side, type, rawPrice, quantity);
        } finally {
            guard.set(false); // always release, even on exception
        }
    }

    /**
     * Submits a cancellation request for an existing resting order.
     * The cancellation is routed through the matching engine so the consumed-CAS
     * prevents any race with an in-flight fill.
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param order the order to cancel (must have OPEN or PARTIALLY_FILLED status)
     * @return {@code true} if the cancellation was enqueued successfully
     */
    public boolean cancelOrder(Order order) {
        if (order.getStatus() != OrderStatus.OPEN &&
                order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            logger.fine("[OrderSubmissionService] Cancel ignored — order " +
                    order.getOrderId() + " is already " + order.getStatus());
            return false;
        }
        order.setStatus(OrderStatus.CANCELLED);
        engine.submitOrder(order);
        return true;
    }

    // ──────────────────────────── Internal submission logic ──────────────────────

    private Order doSubmit(Player player, String symbol, OrderSide side,
                           OrderType type, double rawPrice, int quantity) {

        // ── 1. Asset config lookup ─────────────────────────────────────────────
        AssetConfig assetConfig = config.getAsset(symbol);
        if (assetConfig == null || !assetConfig.isEnabled()) {
            player.sendMessage("§c[BlockStreet] Unknown or disabled asset: " + symbol);
            return null;
        }

        // ── 2. Quantity validation ──────────────────────────────────────────────
        String qtyError = OrderValidator.validateQuantity(quantity,
                assetConfig.getMinQty(), assetConfig.getMaxQty());
        if (qtyError != null) {
            player.sendMessage("§c[BlockStreet] " + qtyError);
            return null;
        }

        // ── 3. Price normalization & validation (LIMIT orders only) ─────────────
        double normalizedPrice = 0.0;
        if (type == OrderType.LIMIT) {
            normalizedPrice = OrderValidator.normalizeToTick(rawPrice, assetConfig.getPriceTick());
            // Notify player if the price was rounded
            if (Math.abs(normalizedPrice - rawPrice) > 0.0001) {
                player.sendMessage(String.format(
                        "§e[BlockStreet] §fPrice adjusted from %.4f to %.4f (nearest tick).",
                        rawPrice, normalizedPrice));
            }
            String priceError = OrderValidator.validatePrice(normalizedPrice,
                    assetConfig.getMinPrice(), assetConfig.getMaxPrice());
            if (priceError != null) {
                player.sendMessage("§c[BlockStreet] " + priceError);
                return null;
            }
        }

        // ── 4. Max open orders guard ────────────────────────────────────────────
        // (Future: count via MatchingEngine.getBook(symbol).countOrdersForPlayer())
        // Placeholder — full per-player order count is implemented when GUI is wired up

        // ── 5. Build the order POJO ─────────────────────────────────────────────
        Order order = new Order(
                UUID.randomUUID(),
                player.getUniqueId(),
                symbol,
                side,
                type,
                normalizedPrice,
                quantity,
                System.nanoTime()
        );

        long durationMs = java.util.concurrent.TimeUnit.DAYS.toMillis(order.getDurationDays());
        order.setExpiresAt(System.currentTimeMillis() + durationMs);


        // ── 6. Phase 2A asset lock ──────────────────────────────────────────────
        // Register in pending map BEFORE locking so PlayerQuitEvent can detect the window (E19)
        pendingLockOrders.put(player.getUniqueId(), order);
        boolean locked;
        try {
            locked = lockAssets(player, order, assetConfig);
        } finally {
            // On failure, remove immediately so quit handler does not try to double-rollback
            if (!order.isAssetsLocked()) pendingLockOrders.remove(player.getUniqueId());
        }
        if (!locked) {
            // lockAssets already sent the player a rejection message
            return null;
        }

        // ── 7. Submit to engine ─────────────────────────────────────────────────
        engine.submitOrder(order);
        // Order is safely in the queue — remove from pending window
        pendingLockOrders.remove(player.getUniqueId());
        player.sendMessage("§a[BlockStreet] §fOrder placed! ID: " +
                order.getOrderId().toString().substring(0, 8) + "…");
        logger.fine("[OrderSubmissionService] Submitted: " + order);
        return order;
    }

    // ──────────────────────────── Phase 2A: Asset Lock ───────────────────────────

    /**
     * Locks the assets required to back an order.
     *
     * <ul>
     *   <li>SELL: removes items from inventory immediately.</li>
     *   <li>LIMIT BUY: withdraws {@code normalizedPrice × qty} from Vault.</li>
     *   <li>MARKET BUY: calculates sweep cost from depth snapshot and withdraws
     *       only the calculable portion.</li>
     * </ul>
     *
     * @return {@code true} if assets were successfully locked and
     *         {@link Order#tryLockAssets()} was called
     */
    private boolean lockAssets(Player player, Order order, AssetConfig assetConfig) {
        UUID   playerId = player.getUniqueId();
        String symbol   = order.getSymbol();
        int    qty      = order.getQuantityRemaining();

        if (order.getSide() == OrderSide.SELL) {
            return lockSellAssets(player, order, assetConfig, qty);

        } else if (order.getType() == OrderType.LIMIT) {
            return lockLimitBuyAssets(player, order, qty);

        } else {
            // MARKET BUY
            return lockMarketBuyAssets(player, order, symbol, qty);
        }
    }

    /** Phase 2A — SELL order: remove items from inventory. */
    private boolean lockSellAssets(Player player, Order order, AssetConfig assetConfig, int qty) {
        Material mat = assetConfig.getMaterial();
        ItemStack toRemove = new ItemStack(mat, qty);

        if (!player.getInventory().containsAtLeast(toRemove, qty)) {
            player.sendMessage("§c[BlockStreet] Insufficient items. You need " +
                    qty + "× " + assetConfig.getDisplayName() + " to place this sell order.");
            order.setStatus(OrderStatus.REJECTED);
            return false;
        }

        // Remove items immediately — they are now escrowed by the plugin
        player.getInventory().removeItem(toRemove);

        double setupFee = order.getLimitPrice() * qty * config.getSetupFeeRate();
        if (setupFee > 0) {
            if (!economy.has(player.getUniqueId(), setupFee)) {
                player.sendMessage("§c[BlockStreet] Insufficient funds for setup fee: " + economy.format(setupFee));
                order.setStatus(OrderStatus.REJECTED);
                // Return items
                player.getInventory().addItem(toRemove);
                return false;
            }
            economy.withdrawPlayer(player.getUniqueId(), setupFee);
        }
        order.setSetupFeePaid(setupFee);
        order.setSellerPremium(player.hasPermission(config.getPremiumPermissionNode()));

        order.tryLockAssets();
        logger.fine("[OrderSubmissionService] SELL lock: removed " + qty + "× " +
                assetConfig.getSymbol() + " from " + player.getName());
        return true;
    }

    /** Phase 2A — LIMIT BUY order: withdraw exact escrow from Vault. */
    private boolean lockLimitBuyAssets(Player player, Order order, int qty) {
        double escrowAmount = order.getLimitPrice() * qty;
        double setupFee   = escrowAmount * config.getSetupFeeRate();
        double totalLock  = escrowAmount + setupFee;

        if (!economy.has(player.getUniqueId(), totalLock)) {
            player.sendMessage(String.format(
                    "§c[BlockStreet] Insufficient funds. You need %s to place this order.",
                    economy.format(totalLock)));
            order.setStatus(OrderStatus.REJECTED);
            return false;
        }

        boolean withdrew = economy.withdrawPlayer(player.getUniqueId(), totalLock);
        if (!withdrew) {
            player.sendMessage("§c[BlockStreet] Failed to reserve funds. Please try again.");
            order.setStatus(OrderStatus.REJECTED);
            return false;
        }

        order.setEscrowAmount(escrowAmount);
        order.setSetupFeePaid(setupFee);
        order.tryLockAssets();
        logger.fine(String.format("[OrderSubmissionService] LIMIT BUY lock: escrowed %s (inc. setup fee) for %s",
                economy.format(totalLock), player.getName()));
        return true;
    }

    /**
     * Phase 2A — MARKET BUY order: estimate sweep cost from the current depth snapshot
     * and withdraw only the calculable portion.
     *
     * <p>If the book is too thin to fill the full order, we lock only what is available.
     * The engine fills what it can; {@link SettlementDispatcher} refunds any unused escrow.
     */
    private boolean lockMarketBuyAssets(Player player, Order order, String symbol, int qty) {
        // Peek depth snapshot (volatile read — safe on Main Thread)
        MarketDepthSnapshot snapshot = null;
        var book = engine.getBook(symbol);
        if (book != null) {
            snapshot = book.getDepthSnapshot();
        }

        double sweepCost = 0.0;
        int    sweepQty  = 0;

        if (snapshot != null && !snapshot.asks().isEmpty()) {
            for (MarketDepthSnapshot.DepthLevel level : snapshot.asks()) {
                int fillable = Math.min(qty - sweepQty, level.totalVolume());
                sweepCost += fillable * level.price();
                sweepQty  += fillable;
                if (sweepQty >= qty) break;
            }
        }

        if (sweepQty == 0) {
            player.sendMessage("§c[BlockStreet] No sell orders available for " + symbol +
                    ". Cannot place market buy order.");
            order.setStatus(OrderStatus.REJECTED);
            return false;
        }

        if (sweepQty < qty) {
            player.sendMessage(String.format(
                    "§e[BlockStreet] §fBook only has %d of %d available. " +
                            "Locking cost for available quantity only.",
                    sweepQty, qty));
        }

        if (!economy.has(player.getUniqueId(), sweepCost)) {
            player.sendMessage(String.format(
                    "§c[BlockStreet] Insufficient funds. Estimated cost: %s",
                    economy.format(sweepCost)));
            order.setStatus(OrderStatus.REJECTED);
            return false;
        }

        boolean withdrew = economy.withdrawPlayer(player.getUniqueId(), sweepCost);
        if (!withdrew) {
            player.sendMessage("§c[BlockStreet] Failed to reserve funds. Please try again.");
            order.setStatus(OrderStatus.REJECTED);
            return false;
        }

        order.setEscrowAmount(sweepCost);
        order.tryLockAssets();
        logger.fine(String.format(
                "[OrderSubmissionService] MARKET BUY lock: escrowed %s for %s (sweepQty=%d/%d)",
                economy.format(sweepCost), player.getName(), sweepQty, qty));
        return true;
    }

    // ──────────────────────────── Rollback (PlayerQuit guard) ───────────────────

    /**
     * Rolls back an asset lock if the player disconnects after Phase 2A but before
     * the order enters the submission queue (edge case E19 in the plan).
     *
     * <p>Should be called from {@code PlayerQuitEvent} if the player is in the middle
     * of order submission.
     *
     * @param order the order whose assets should be returned
     */
    public void rollbackLock(Order order) {
        if (!order.isAssetsLocked()) return;

        if (order.getSide() == OrderSide.SELL) {
            // Return items via mailbox (player is offline)
            mailboxLedger.addItemEntry(
                    order.getPlayerId(),
                    ItemFactory.create(order.getSymbol(), order.getQuantityRemaining()),
                    "ROLLBACK",
                    order.getOrderId()
            );
        } else {
            // Refund escrow
            double refund = order.getEscrowAmount();
            if (refund > 0.001) {
                economy.depositPlayer(order.getPlayerId(), refund);
            }
        }
        order.setStatus(OrderStatus.CANCELLED);
        logger.warning("[OrderSubmissionService] Rolled back lock for disconnected player: " +
                order.getPlayerId() + " order=" + order.getOrderId());
    }

    /**
     * Edge case E19 — called from {@code PlayerQuitEvent}.
     *
     * <p>If the player is currently in the Phase 2A asset-lock window (funds/items
     * have been taken but the order has not yet entered the engine queue), this method
     * retrieves their pending order from the tracking map, calls {@link #rollbackLock},
     * and removes the entry from the map so no further action is taken.
     *
     * @param playerId UUID of the disconnecting player
     * @return {@code true} if a pending lock was found and rolled back; {@code false} if
     *         the player was not in the lock window (normal case)
     */
    public boolean rollbackPendingLock(UUID playerId) {
        Order pending = pendingLockOrders.remove(playerId);
        if (pending == null) return false;
        rollbackLock(pending);
        return true;
    }
}
