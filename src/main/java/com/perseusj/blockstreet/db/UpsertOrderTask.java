package com.perseusj.blockstreet.db;

import com.perseusj.blockstreet.engine.model.Order;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Upserts a resting order's state into the {@code resting_orders} table.
 *
 * <p>Called in three scenarios:
 * <ol>
 *   <li><b>Order enters book</b>: status = OPEN, qty_remaining = full quantity.</li>
 *   <li><b>Partial fill</b>: status = PARTIALLY_FILLED, qty_remaining decremented.</li>
 *   <li><b>Order terminal</b> (filled, cancelled, rejected): status updated, qty_remaining = 0.</li>
 * </ol>
 *
 * <p>Uses {@code INSERT OR REPLACE} (SQLite) which functions as an upsert on the
 * primary key ({@code order_id}). On MySQL the SQL would use {@code ON DUPLICATE KEY UPDATE};
 * SQLite's {@code INSERT OR REPLACE} is equivalent for our schema since all non-PK fields
 * are always overwritten.
 *
 * <p>{@code submitted_at} is stored as epoch <em>milliseconds</em> for human-readable
 * admin queries. The engine uses {@link System#nanoTime()} for FIFO tiebreaking internally;
 * that nanosecond value is not stored (it is only meaningful within a single JVM session).
 */
public final class UpsertOrderTask implements DbWriteTask {

    private static final String SQL = """
            INSERT OR REPLACE INTO resting_orders
                (order_id, player_id, symbol, side, order_type,
                 limit_price, qty_original, qty_remaining,
                 status, submitted_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final Order order;

    /**
     * @param order the order whose current state should be persisted
     */
    public UpsertOrderTask(Order order) {
        this.order = order;
    }

    @Override
    public void execute(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setString(1,  order.getOrderId().toString());
            ps.setString(2,  order.getPlayerId().toString());
            ps.setString(3,  order.getSymbol());
            ps.setString(4,  order.getSide().name());
            ps.setString(5,  order.getType().name());
            ps.setDouble(6,  order.getLimitPrice());
            ps.setInt(7,     order.getQuantityOriginal());
            ps.setInt(8,     order.getQuantityRemaining());
            ps.setString(9,  order.getStatus().name());
            // submittedAt is System.nanoTime() — convert to millis approximation for storage
            // We store System.currentTimeMillis() at task-creation time as a human-readable timestamp
            ps.setLong(10,   System.currentTimeMillis() - (System.nanoTime() - order.getSubmittedAt()) / 1_000_000L);
            ps.setLong(11,   System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
