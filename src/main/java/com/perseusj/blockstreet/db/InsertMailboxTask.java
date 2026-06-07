package com.perseusj.blockstreet.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Inserts an offline item delivery record into the {@code mailbox_items} table.
 *
 * <p>Written when:
 * <ul>
 *   <li>A fill settlement targets an <b>offline player</b> — items cannot be delivered
 *       to inventory directly, so they are persisted here and delivered on next login
 *       via {@code PlayerJoinEvent}.</li>
 *   <li>An online player's inventory is <b>full</b> at delivery time — overflow items
 *       are stored here as a fallback.</li>
 * </ul>
 *
 * <p>The {@code delivered} column defaults to {@code 0} (false). On successful delivery
 * at login, {@code MailboxManager} updates the row to {@code delivered = 1}. Items with
 * {@code delivered = 0} are loaded on startup to handle crash-before-delivery scenarios.
 */
public final class InsertMailboxTask implements DbWriteTask {

    private static final String SQL = """
            INSERT INTO mailbox_items (player_id, symbol, quantity, stored_at, delivered)
            VALUES (?, ?, ?, ?, 0)
            """;

    private final UUID playerId;
    private final String symbol;
    private final int quantity;

    /**
     * @param playerId UUID of the player who should receive the items
     * @param symbol   asset symbol (e.g. "DIAMOND")
     * @param quantity number of items to hold in the mailbox
     */
    public InsertMailboxTask(UUID playerId, String symbol, int quantity) {
        this.playerId = playerId;
        this.symbol   = symbol;
        this.quantity = quantity;
    }

    @Override
    public void execute(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, symbol);
            ps.setInt(3,    quantity);
            ps.setLong(4,   System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
