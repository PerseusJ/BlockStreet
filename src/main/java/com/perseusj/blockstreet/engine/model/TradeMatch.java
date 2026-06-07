package com.perseusj.blockstreet.engine.model;

import java.util.UUID;

/**
 * Immutable value object produced by the matching engine for each partial or full fill.
 *
 * <p>A single taker order may produce multiple {@code TradeMatch} records — one for each
 * price level swept during matching. All fields reflect the state at the moment of execution.
 *
 * <h2>Execution Price Rule</h2>
 * The {@code executionPrice} is always the <em>maker's</em> limit price (price-time priority).
 * The taker's limit price is irrelevant to execution economics; it is only used for the
 * price-crossability check before matching begins.
 *
 * <h2>Fee Accounting</h2>
 * {@code takerFeeRate} is a snapshot of the fee rate at the moment of the trade (from config).
 * The actual fee deduction is performed by {@code SettlementDispatcher} on the Main Thread;
 * this record carries the rate for audit trail purposes only.
 *
 * @param tradeId         unique identifier for this fill event (stored in trade_history)
 * @param symbol          asset symbol (e.g. "DIAMOND")
 * @param makerOrder      the resting (passive) order that provided liquidity
 * @param takerOrder      the incoming (active) order that consumed liquidity
 * @param executionPrice  the maker's limit price — always the transaction price
 * @param quantityFilled  number of units exchanged in this fill event
 * @param takerFeeRate    fee rate applied to the taker at execution time (e.g. 0.01 for 1%)
 * @param executedAt      {@link System#currentTimeMillis()} when the match was made
 */
public record TradeMatch(
        UUID tradeId,
        String symbol,
        Order makerOrder,
        Order takerOrder,
        double executionPrice,
        int quantityFilled,
        double takerFeeRate,
        long executedAt
) {
    /**
     * Convenience: gross value of this fill (before fees).
     *
     * @return {@code executionPrice × quantityFilled}
     */
    public double grossValue() {
        return executionPrice * quantityFilled;
    }

    /**
     * Convenience: taker fee amount destroyed by this fill.
     *
     * @return {@code grossValue() × takerFeeRate}
     */
    public double takerFeeAmount() {
        return grossValue() * takerFeeRate;
    }
}
