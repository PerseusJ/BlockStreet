package com.perseusj.blockstreet.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles operations for the player_cache table.
 */
public final class PlayerCacheDao {

    private final DatabaseService db;
    private final Logger logger;

    public PlayerCacheDao(DatabaseService db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * Updates or inserts a player's cache entry when they join.
     */
    public void updatePlayerCache(UUID playerId, String playerName) {
        String sql = """
            INSERT INTO player_cache (player_id, player_name, last_seen, resource_pack_accepted)
            VALUES (?, ?, ?, 0)
            ON CONFLICT(player_id) DO UPDATE SET 
                player_name = excluded.player_name, 
                last_seen = excluded.last_seen
            """;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, playerName);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[BlockStreet] Failed to update player cache for " + playerId + ": " + e.getMessage());
        }
    }

    /**
     * Updates the player's resource pack accepted status.
     */
    public void updateResourcePackStatus(UUID playerId, boolean accepted) {
        String sql = "UPDATE player_cache SET resource_pack_accepted = ? WHERE player_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accepted ? 1 : 0);
            ps.setString(2, playerId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[BlockStreet] Failed to update RP status for " + playerId + ": " + e.getMessage());
        }
    }
}
