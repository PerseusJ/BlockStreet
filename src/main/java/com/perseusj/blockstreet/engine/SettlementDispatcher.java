package com.perseusj.blockstreet.engine;

import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.TradeMatch;
import com.perseusj.blockstreet.managers.MailboxManager;
import com.perseusj.blockstreet.managers.VaultEconomyService;
import com.perseusj.blockstreet.utils.ItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bridges the MatchingEngine thread and the Server Main Thread for settlement.
 *
 * <h2>Responsibility</h2>
 * After the matching engine executes a fill (or cancellation), it calls
 * {@link #dispatch(TradeMatch)} <em>from the engine thread</em>. This class schedules
 * the actual Vault and inventory operations to run on the <strong>Main Thread</strong>
 * via {@code Bukkit.getScheduler().runTask()}.
 *
 * <h2>Maker–Taker Fee Model</h2>
 * <ul>
 *   <li><strong>Makers</strong> (resting orders) pay zero fee — they add liquidity.</li>
 *   <li><strong>Takers</strong> (incoming orders) pay a percentage of the gross trade
 *       value. This fee is <em>permanently destroyed</em> (not deposited anywhere) and
 *       serves as the primary economy drain.</li>
 * </ul>
 *
 * <h2>Cancellation Detection</h2>
 * The engine signals a cancellation by producing a {@link TradeMatch} where
 * {@code makerOrder == takerOrder} (same reference) and {@code quantityFilled == 0}.
 * {@link #dispatch} detects this and calls the refund path instead.
 *
 * <h2>Thread Safety</h2>
 * {@link #dispatch} is called from the engine thread (or any thread) and must not touch
 * Bukkit APIs directly. All Bukkit-touching code runs in the {@code runTask} lambda,
 * which executes on the Main Thread.
 */
public final class SettlementDispatcher {

    private final Plugin             plugin;
    private final VaultEconomyService economy;
    private final ConfigManager      config;
    private final Logger             logger;

    // ──────────────────────────── Constructor ────────────────────────────────────

    public SettlementDispatcher(Plugin plugin, VaultEconomyService economy,
                                ConfigManager config) {
        this.plugin  = plugin;
        this.economy = economy;
        this.config  = config;
        this.logger  = plugin.getLogger();
    }

    // ──────────────────────────── Public dispatch (any thread) ───────────────────

    /**
     * Schedules settlement of a fill or cancellation event on the Main Thread.
     *
     * <p>Called from the MatchingEngine thread. Returns immediately — the actual
     * Vault/inventory operations happen asynchronously on the next main-thread tick.
     *
     * @param trade the fill or cancellation event produced by the engine
     */
    public void dispatch(TradeMatch trade) {
        Bukkit.getScheduler().runTask(plugin, () -> settle(trade));
    }

    // ──────────────────────────── Settlement logic (Main Thread) ─────────────────

    /**
     * Performs the actual settlement. <strong>Main Thread only.</strong>
     */
    private void settle(TradeMatch trade) {
        // ── Cancellation detection: maker == taker reference, qty == 0 ──────────
        if (trade.makerOrder() == trade.takerOrder() && trade.quantityFilled() == 0) {
            handleCancellationRefund(trade.makerOrder());
            return;
        }

        // ── Normal fill settlement ────────────────────────────────────────────────
        settleMaker(trade);
        settleTaker(trade);

        // ── Log the fee sink for admin transparency ───────────────────────────────
        double grossValue = trade.executionPrice() * trade.quantityFilled();
        double feeAmount  = grossValue * trade.takerFeeRate();
        logger.fine(String.format(
                "[SettlementDispatcher] Trade %s | Symbol=%s | Qty=%d | ExecPrice=%.4f" +
                        " | TakerFee=%.4f (%.2f%%)",
                trade.tradeId(), trade.symbol(), trade.quantityFilled(),
                trade.executionPrice(), feeAmount, trade.takerFeeRate() * 100));
    }

    // ──────────────────────────── Maker settlement ───────────────────────────────

    /**
     * Settles the maker (resting) side of a fill. Makers pay zero fee.
     *
     * <ul>
     *   <li>Maker SELL: deposit gross proceeds (exec price × qty filled).</li>
     *   <li>Maker BUY: deliver items + refund overpayment (if exec price < limit price).</li>
     * </ul>
     */
    private void settleMaker(TradeMatch trade) {
        Order  maker      = trade.makerOrder();
        double grossValue = trade.executionPrice() * trade.quantityFilled();

        if (maker.getSide() == OrderSide.SELL) {
            // Maker SOLD items (they were locked at Phase 2A) → receive full gross currency
            economy.depositPlayer(maker.getPlayerId(), grossValue);

        } else {
            // Maker BOUGHT → deliver items
            deliverItems(maker.getPlayerId(), trade.symbol(), trade.quantityFilled(), "maker fill");

            // Refund any overpayment: maker locked limitPrice × qty at Phase 2A
            // If executed below the limit price, the difference is refunded
            double overpay = (maker.getLimitPrice() - trade.executionPrice()) * trade.quantityFilled();
            if (overpay > 0.001) { // float epsilon guard
                economy.depositPlayer(maker.getPlayerId(), overpay);
            }
        }

        // Mark maker consumed when fully filled; partial fills leave it in the book
        if (maker.getQuantityRemaining() == 0) {
            maker.tryConsume(); // idempotent — CAS false→true exactly once
        }
    }

    // ──────────────────────────── Taker settlement ───────────────────────────────

    /**
     * Settles the taker (incoming) side of a fill. Takers pay the configured fee.
     *
     * <ul>
     *   <li>Taker SELL: deposit net proceeds (gross − fee). Items were removed at Phase 2A.</li>
     *   <li>Taker BUY: deliver items. Refund unused escrow (for partial fills or market orders
     *       that filled below the escrow ceiling). The fee is the portion of escrow that is
     *       <em>not</em> refunded — it vanishes.</li>
     * </ul>
     */
    private void settleTaker(TradeMatch trade) {
        Order  taker      = trade.takerOrder();
        double grossValue = trade.executionPrice() * trade.quantityFilled();
        double feeRate    = trade.takerFeeRate();
        double feeAmount  = grossValue * feeRate;   // destroyed — not deposited anywhere
        double netValue   = grossValue - feeAmount;

        if (taker.getSide() == OrderSide.SELL) {
            // Taker SOLD → deposit net proceeds (fee withheld and destroyed)
            economy.depositPlayer(taker.getPlayerId(), netValue);
            // Items were already removed from inventory during Phase 2A — no action needed here

        } else {
            // Taker BOUGHT → deliver items
            deliverItems(taker.getPlayerId(), trade.symbol(), trade.quantityFilled(), "taker fill");

            // Escrow refund logic:
            // The taker locked escrowAmount upfront (Phase 2A).
            // Each fill costs: executionPrice × fillQty  (the fee is the cost of being the taker).
            // We track consumed escrow to compute cumulative refunds across partial fills.
            double escrowUsed = grossValue; // fee is simply NOT refunded — it vanishes
            taker.incrementEscrowConsumed(escrowUsed);

            // Calculate remaining refund only when the order is terminal (fully filled / market done)
            // For partial fills on a resting LIMIT order, we defer the refund until it's fully consumed
            if (taker.getQuantityRemaining() == 0) {
                double refund = taker.getEscrowAmount() - taker.getEscrowAmountConsumedSoFar();
                if (refund > 0.001) {
                    economy.depositPlayer(taker.getPlayerId(), refund);
                }
                taker.tryConsume();
            }
        }
    }

    // ──────────────────────────── Cancellation refund ────────────────────────────

    /**
     * Refunds assets to a player after a resting order is cancelled.
     *
     * <p>The engine already performed the {@code consumed} CAS — if it succeeded, this method
     * runs. The refund is straightforward:
     * <ul>
     *   <li>SELL cancellation: return the locked items (they were removed from inventory at Phase 2A).</li>
     *   <li>BUY cancellation: refund the remaining escrow ({@code limitPrice × quantityRemaining}).</li>
     * </ul>
     *
     * @param order the cancelled order
     */
    private void handleCancellationRefund(Order order) {
        int qtyRemaining = order.getQuantityRemaining();

        if (order.getSide() == OrderSide.SELL) {
            // Return locked items
            deliverItems(order.getPlayerId(), order.getSymbol(), qtyRemaining, "cancellation refund");

        } else {
            // BUY cancellation: refund remaining escrow
            // Escrow for filled portions has already been consumed; refund only what's left
            double refundAmt = order.getLimitPrice() * qtyRemaining;
            if (refundAmt > 0.001) {
                boolean ok = economy.depositPlayer(order.getPlayerId(), refundAmt);
                if (ok) {
                    logger.fine(String.format(
                            "[SettlementDispatcher] BUY cancel refund: player=%s refund=%.4f symbol=%s qty=%d",
                            order.getPlayerId(), refundAmt, order.getSymbol(), qtyRemaining));
                }
            }
        }

        // Notify player if online
        Player player = Bukkit.getPlayer(order.getPlayerId());
        if (player != null && player.isOnline()) {
            player.sendMessage("§e[BlockStreet] §fOrder " +
                    order.getOrderId().toString().substring(0, 8) + "… cancelled. Refund processed.");
        }
    }

    // ──────────────────────────── Item delivery helper ────────────────────────────

    /**
     * Delivers {@code qty} units of {@code symbol} to the player.
     * If the player is offline or their inventory is full, items go to the
     * {@link MailboxManager}.
     *
     * <p><strong>Main Thread only.</strong>
     *
     * @param playerId  recipient player UUID
     * @param symbol    asset symbol (e.g. "DIAMOND")
     * @param qty       number of items to deliver
     * @param context   short description for log messages (e.g. "maker fill")
     */
    private void deliverItems(UUID playerId, String symbol, int qty, String context) {
        ItemStack items = ItemFactory.create(symbol, qty);
        Player player   = Bukkit.getPlayer(playerId);

        if (player != null && player.isOnline()) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(items);
            if (!overflow.isEmpty()) {
                // Inventory full — route overflow to mailbox
                Collection<ItemStack> overflowItems = overflow.values();
                MailboxManager.getInstance().storeItems(playerId, overflowItems);
                player.sendMessage("§e[BlockStreet] §fInventory full! " +
                        overflow.size() + " item stack(s) stored in your mailbox.");
            }
        } else {
            // Player offline — store all items in mailbox for next login
            MailboxManager.getInstance().storeItems(playerId, List.of(items));
            logger.fine(String.format(
                    "[SettlementDispatcher] Player %s offline during %s; items stored in mailbox.",
                    playerId, context));
        }
    }
}
