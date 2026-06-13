package com.perseusj.blockstreet.engine;

import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.db.MailboxLedgerService;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderStatus;
import com.perseusj.blockstreet.engine.model.TradeMatch;
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

    private final Plugin               plugin;
    private final MailboxLedgerService mailboxLedger;
    private final ConfigManager        config;
    private final Logger               logger;

    // ──────────────────────────── Constructor ────────────────────────────────────

    public SettlementDispatcher(Plugin plugin, MailboxLedgerService mailboxLedger,
                                ConfigManager config) {
        this.plugin        = plugin;
        this.mailboxLedger = mailboxLedger;
        this.config        = config;
        this.logger        = plugin.getLogger();
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
        logger.fine(String.format(
                "[SettlementDispatcher] Trade %s | Symbol=%s | Qty=%d | ExecPrice=%.4f",
                trade.tradeId(), trade.symbol(), trade.quantityFilled(),
                trade.executionPrice()));
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
            // Maker SOLD items → apply sales tax and route net to mailbox
            double salesTaxRate = maker.isSellerPremium() ? config.getPremiumTaxRate() : config.getStandardTaxRate();
            double tax          = grossValue * salesTaxRate;
            double netValue     = grossValue - tax;
            mailboxLedger.addCurrencyEntry(maker.getPlayerId(), netValue, "FILL", maker.getOrderId());

        } else {
            // Maker BOUGHT → deliver items to mailbox
            mailboxLedger.addItemEntry(maker.getPlayerId(), ItemFactory.create(trade.symbol(), trade.quantityFilled()), "FILL", maker.getOrderId());

            // Refund any overpayment: maker locked limitPrice × qty at Phase 2A
            double overpay = (maker.getLimitPrice() - trade.executionPrice()) * trade.quantityFilled();
            if (overpay > 0.001) { // float epsilon guard
                mailboxLedger.addCurrencyEntry(maker.getPlayerId(), overpay, "FILL", maker.getOrderId());
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

        if (taker.getSide() == OrderSide.SELL) {
            // Taker SOLD → apply sales tax and route net to mailbox
            double salesTaxRate = taker.isSellerPremium() ? config.getPremiumTaxRate() : config.getStandardTaxRate();
            double tax          = grossValue * salesTaxRate;
            double netValue     = grossValue - tax;
            mailboxLedger.addCurrencyEntry(taker.getPlayerId(), netValue, "FILL", taker.getOrderId());

        } else {
            // Taker BOUGHT → deliver items to mailbox
            mailboxLedger.addItemEntry(taker.getPlayerId(), ItemFactory.create(trade.symbol(), trade.quantityFilled()), "FILL", taker.getOrderId());

            // The taker locked escrowAmount upfront. Fee is not calculated here anymore,
            // escrow unused is simply refunded.
            double escrowUsed = grossValue;
            taker.incrementEscrowConsumed(escrowUsed);

            if (taker.getQuantityRemaining() == 0) {
                double refund = taker.getEscrowAmount() - taker.getEscrowAmountConsumedSoFar();
                if (refund > 0.001) {
                    mailboxLedger.addCurrencyEntry(taker.getPlayerId(), refund, "FILL", taker.getOrderId());
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
        String source = order.getStatus() == OrderStatus.EXPIRED ? "EXPIRATION" : "CANCEL";

        if (order.getSide() == OrderSide.SELL) {
            // Return locked items to mailbox
            mailboxLedger.addItemEntry(order.getPlayerId(), ItemFactory.create(order.getSymbol(), qtyRemaining), source, order.getOrderId());
        } else {
            // BUY cancellation: refund remaining escrow
            double refundAmt = order.getLimitPrice() * qtyRemaining;
            if (refundAmt > 0.001) {
                mailboxLedger.addCurrencyEntry(order.getPlayerId(), refundAmt, source, order.getOrderId());
            }
        }
    }
}
