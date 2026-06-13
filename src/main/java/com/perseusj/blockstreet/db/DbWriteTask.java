package com.perseusj.blockstreet.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Sealed interface for all asynchronous database write operations queued via
 * {@link DbWriteQueue}.
 *
 * <p>Each implementation knows exactly which SQL statement(s) to execute and which
 * parameters to bind. The batch writer calls {@link #execute(Connection)} inside
 * a single transaction covering all tasks in a flush batch — if any task throws,
 * the entire batch is rolled back and re-queued into the dead-letter queue.
 *
 * <p>Permitted implementations:
 * <ul>
 *   <li>{@link InsertTradeTask} — inserts a completed fill into {@code trade_history}</li>
 *   <li>{@link UpsertOrderTask} — inserts or updates a resting order in {@code resting_orders}</li>
 *   <li>{@link InsertMailboxTask} — records offline item deliveries in {@code mailbox_items}</li>
 * </ul>
 */
public interface DbWriteTask {

    /**
     * Executes the SQL operation(s) for this task using the provided connection.
     * The connection is in manual-commit mode; the caller commits or rolls back.
     *
     * @param conn an open, auto-commit-disabled JDBC connection
     * @throws SQLException if the SQL execution fails
     */
    void execute(Connection conn) throws SQLException;
}
