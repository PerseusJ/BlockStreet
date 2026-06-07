package com.perseusj.blockstreet.db;

import com.perseusj.blockstreet.engine.model.TradeMatch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Inserts a completed fill event into the {@code trade_history} table.
 *
 * <p>One {@code InsertTradeTask} is produced by the matching engine for every
 * partial or full fill ({@link TradeMatch}). The batch writer groups multiple
 * inserts into a single JDBC transaction for efficiency.
 *
 * <p>The {@code taker_fee} column records the economy-sink amount permanently
 * destroyed by this trade — never deposited to any player. This provides an
 * auditable trail for server administrators.
 */
public final class InsertTradeTask implements DbWriteTask {

    private static final String SQL = """
            INSERT OR IGNORE INTO trade_history
                (trade_id, symbol, maker_order_id, taker_order_id,
                 maker_player_id, taker_player_id,
                 execution_price, qty_filled, taker_fee, fee_rate_pct, executed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final TradeMatch trade;

    /**
     * @param trade the fill record produced by the matching engine
     */
    public InsertTradeTask(TradeMatch trade) {
        this.trade = trade;
    }

    @Override
    public void execute(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setString(1,  trade.tradeId().toString());
            ps.setString(2,  trade.symbol());
            ps.setString(3,  trade.makerOrder().getOrderId().toString());
            ps.setString(4,  trade.takerOrder().getOrderId().toString());
            ps.setString(5,  trade.makerOrder().getPlayerId().toString());
            ps.setString(6,  trade.takerOrder().getPlayerId().toString());
            ps.setDouble(7,  trade.executionPrice());
            ps.setInt(8,     trade.quantityFilled());
            ps.setDouble(9,  trade.takerFeeAmount());       // currency destroyed (economy sink)
            ps.setDouble(10, trade.takerFeeRate() * 100.0); // stored as % for human-readable audit
            ps.setLong(11,   trade.executedAt());
            ps.executeUpdate();
        }
    }
}
