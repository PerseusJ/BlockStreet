package com.perseusj.blockstreet.db;

import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.config.ConfigManager;
import com.perseusj.blockstreet.engine.OrderValidator;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderStatus;
import com.perseusj.blockstreet.engine.model.OrderType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages the JDBC connection pool and database schema for BlockStreet.
 *
 * <h2>Connection pool</h2>
 * <p>Uses HikariCP for connection pooling. SQLite only supports a single concurrent
 * writer, so {@code maximumPoolSize} is forced to {@code 1} for SQLite. MySQL mode
 * allows up to {@code 5} connections (configurable).
 *
 * <h2>SQLite WAL mode</h2>
 * <p>Write-Ahead Logging ({@code PRAGMA journal_mode=WAL}) is enabled immediately after
 * the pool is opened. WAL allows concurrent reads while the writer is active, which is
 * critical when the GUI history query runs at the same time as the DbWriteQueue flush.
 *
 * <h2>Schema management</h2>
 * <p>{@link #initSchema()} is idempotent — uses {@code CREATE TABLE IF NOT EXISTS} and
 * {@code CREATE INDEX IF NOT EXISTS}. Safe to call on every plugin enable.
 *
 * <h2>Reboot recovery</h2>
 * <p>{@link #loadOpenOrders(ConfigManager)} loads all orders with status {@code OPEN} or
 * {@code PARTIALLY_FILLED} from {@code resting_orders}, re-normalizes their limit prices
 * to the current config tick (in case the tick changed during downtime), marks assets as
 * locked (they were locked pre-shutdown), and returns them for re-submission to the engine.
 */
public final class DatabaseService {

    // ──────────────────────────── Schema DDL ─────────────────────────────────────

    private static final String DDL_RESTING_ORDERS = """
            CREATE TABLE IF NOT EXISTS resting_orders (
                order_id        TEXT PRIMARY KEY,
                player_id       TEXT NOT NULL,
                symbol          TEXT NOT NULL,
                side            TEXT NOT NULL,
                order_type      TEXT NOT NULL,
                limit_price     REAL NOT NULL,
                qty_original    INTEGER NOT NULL,
                qty_remaining   INTEGER NOT NULL,
                status          TEXT NOT NULL,
                submitted_at    INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL
            )""";

    private static final String DDL_IDX_RESTING_PLAYER =
            "CREATE INDEX IF NOT EXISTS idx_resting_player ON resting_orders(player_id)";

    private static final String DDL_IDX_RESTING_SYMBOL_STATUS =
            "CREATE INDEX IF NOT EXISTS idx_resting_symbol_status ON resting_orders(symbol, status)";

    private static final String DDL_TRADE_HISTORY = """
            CREATE TABLE IF NOT EXISTS trade_history (
                trade_id        TEXT PRIMARY KEY,
                symbol          TEXT NOT NULL,
                maker_order_id  TEXT NOT NULL,
                taker_order_id  TEXT NOT NULL,
                maker_player_id TEXT NOT NULL,
                taker_player_id TEXT NOT NULL,
                execution_price REAL NOT NULL,
                qty_filled      INTEGER NOT NULL,
                taker_fee       REAL NOT NULL,
                fee_rate_pct    REAL NOT NULL,
                executed_at     INTEGER NOT NULL
            )""";

    private static final String DDL_IDX_TRADE_SYMBOL =
            "CREATE INDEX IF NOT EXISTS idx_trade_symbol ON trade_history(symbol, executed_at DESC)";

    private static final String DDL_IDX_TRADE_PLAYER =
            "CREATE INDEX IF NOT EXISTS idx_trade_player ON trade_history(maker_player_id, taker_player_id)";

    private static final String DDL_MAILBOX_ITEMS = """
            CREATE TABLE IF NOT EXISTS mailbox_items (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                player_id   TEXT NOT NULL,
                symbol      TEXT NOT NULL,
                quantity    INTEGER NOT NULL,
                stored_at   INTEGER NOT NULL,
                delivered   INTEGER NOT NULL DEFAULT 0
            )""";

    private static final String DDL_IDX_MAILBOX_PLAYER =
            "CREATE INDEX IF NOT EXISTS idx_mailbox_player ON mailbox_items(player_id, delivered)";

    // ──────────────────────────── WAL + cache pragmas ────────────────────────────

    private static final String PRAGMA_WAL   = "PRAGMA journal_mode=WAL";
    private static final String PRAGMA_SYNC  = "PRAGMA synchronous=NORMAL";
    private static final String PRAGMA_CACHE = "PRAGMA cache_size=-8000";   // 8 MB page cache

    // ──────────────────────────── Recovery query ─────────────────────────────────

    private static final String SELECT_OPEN_ORDERS = """
            SELECT order_id, player_id, symbol, side, order_type,
                   limit_price, qty_original, qty_remaining, status, submitted_at
            FROM resting_orders
            WHERE status IN ('OPEN', 'PARTIALLY_FILLED')
            ORDER BY submitted_at ASC
            """;

    // ──────────────────────────── Fields ─────────────────────────────────────────

    private final Logger logger;
    private final File dataFolder;

    private HikariDataSource dataSource;

    // ──────────────────────────── Constructor ────────────────────────────────────

    /**
     * @param dataFolder the plugin's data folder (e.g. {@code plugins/BlockStreet/})
     * @param logger     plugin logger
     */
    public DatabaseService(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger     = logger;
    }

    // ──────────────────────────── Lifecycle ──────────────────────────────────────

    /**
     * Opens the HikariCP connection pool for SQLite and applies WAL pragmas.
     * Must be called on the Main Thread during plugin enable before any DB operations.
     *
     * @throws RuntimeException if the pool cannot be opened (plugin should disable itself)
     */
    public void init() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        String dbPath = new File(dataFolder, "blockstreet.db").getAbsolutePath();
        logger.info("[BlockStreet] Initializing SQLite database at: " + dbPath);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:sqlite:" + dbPath);
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(1);           // SQLite: single-writer only
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(5_000);
        hikari.setMaxLifetime(600_000);
        hikari.setPoolName("BlockStreet-SQLite");

        // SQLite-specific connection init: apply WAL mode on each new connection
        hikari.setConnectionInitSql(
                PRAGMA_WAL + "; " + PRAGMA_SYNC + "; " + PRAGMA_CACHE);

        // Disable auto-commit — we manage transactions explicitly in DbWriteQueue
        hikari.setAutoCommit(true); // HikariCP default; each getConnection() call honors this

        dataSource = new HikariDataSource(hikari);
        logger.info("[BlockStreet] SQLite connection pool opened (WAL mode).");
    }

    /**
     * Closes the connection pool. Call during plugin disable after {@link DbWriteQueue#shutdown()}.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[BlockStreet] SQLite connection pool closed.");
        }
    }

    /**
     * Creates all required tables and indexes if they do not already exist.
     * Idempotent — safe to call on every plugin enable.
     *
     * @throws SQLException if schema creation fails
     */
    public void initSchema() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Apply WAL pragmas explicitly (some JDBC drivers skip connectionInitSql)
            stmt.execute(PRAGMA_WAL);
            stmt.execute(PRAGMA_SYNC);
            stmt.execute(PRAGMA_CACHE);

            // Create tables
            stmt.execute(DDL_RESTING_ORDERS);
            stmt.execute(DDL_TRADE_HISTORY);
            stmt.execute(DDL_MAILBOX_ITEMS);

            // Create indexes
            stmt.execute(DDL_IDX_RESTING_PLAYER);
            stmt.execute(DDL_IDX_RESTING_SYMBOL_STATUS);
            stmt.execute(DDL_IDX_TRADE_SYMBOL);
            stmt.execute(DDL_IDX_TRADE_PLAYER);
            stmt.execute(DDL_IDX_MAILBOX_PLAYER);
        }
        logger.info("[BlockStreet] Database schema initialized.");
    }

    // ──────────────────────────── Connection access ───────────────────────────────

    /**
     * Borrows a connection from the pool. The caller is responsible for closing it
     * (use try-with-resources to return it to the pool automatically).
     *
     * @return an open JDBC connection
     * @throws SQLException if the pool is exhausted or the connection is unavailable
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // ──────────────────────────── Reboot recovery ────────────────────────────────

    /**
     * Loads all resting orders (status OPEN or PARTIALLY_FILLED) from the database,
     * ready for re-submission to the matching engine after a server restart.
     *
     * <p>Re-applies price tick normalization in case the configured tick changed while
     * the server was offline. Orders whose symbol is no longer in the config are
     * logged as warnings and skipped (they remain in the DB with their original status).
     *
     * <p>This method performs blocking JDBC reads — call it on an async Bukkit task,
     * not the Main Thread.
     *
     * @param configManager the loaded config providing asset tick sizes
     * @return list of {@link Order} instances with {@code assetsLocked = true},
     *         ready to pass to {@link com.perseusj.blockstreet.engine.MatchingEngine#submitOrder}
     */
    public List<Order> loadOpenOrders(ConfigManager configManager) {
        List<Order> orders = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_OPEN_ORDERS);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                try {
                    Order order = mapRowToOrder(rs, configManager);
                    if (order != null) {
                        order.tryLockAssets(); // mark assets as locked (they were locked pre-shutdown)
                        orders.add(order);
                    }
                } catch (Exception e) {
                    logger.warning("[BlockStreet] Failed to restore order "
                            + rs.getString("order_id") + ": " + e.getMessage());
                }
            }

        } catch (SQLException e) {
            logger.severe("[BlockStreet] Failed to load open orders from DB: " + e.getMessage());
        }

        logger.info("[BlockStreet] Restored " + orders.size() + " resting order(s) from DB.");
        return orders;
    }

    /**
     * Maps a single {@link ResultSet} row to an {@link Order} instance.
     * Returns {@code null} if the row references an unknown or disabled symbol.
     */
    private Order mapRowToOrder(ResultSet rs, ConfigManager configManager) throws SQLException {
        String symbol = rs.getString("symbol");
        AssetConfig asset = configManager.getAsset(symbol);

        if (asset == null) {
            logger.warning("[BlockStreet] Skipping resting order for unknown symbol '"
                    + symbol + "' — remove manually or re-add the asset config.");
            return null;
        }

        UUID orderId  = UUID.fromString(rs.getString("order_id"));
        UUID playerId = UUID.fromString(rs.getString("player_id"));

        OrderSide side = OrderSide.valueOf(rs.getString("side"));
        OrderType type = OrderType.valueOf(rs.getString("order_type"));

        // Re-normalize price to current tick in case the config changed during downtime
        double rawPrice  = rs.getDouble("limit_price");
        double tickPrice = type == OrderType.MARKET
                ? 0.0
                : OrderValidator.normalizeToTick(rawPrice, asset.getPriceTick());

        int qtyOriginal  = rs.getInt("qty_original");
        int qtyRemaining = rs.getInt("qty_remaining");

        // Use stored submitted_at as nanoTime approximation (already in millis from DB)
        // We shift it far into the past so recovered orders sort before any new submissions
        long submittedAt = rs.getLong("submitted_at") * 1_000_000L; // millis → nanos approx

        Order order = new Order(
                orderId, playerId, symbol,
                side, type,
                tickPrice, qtyOriginal,
                submittedAt
        );

        // Restore partial fill state
        if (qtyRemaining < qtyOriginal) {
            int alreadyFilled = qtyOriginal - qtyRemaining;
            order.decrementQuantityRemaining(alreadyFilled);
            order.setStatus(OrderStatus.PARTIALLY_FILLED);
        }

        return order;
    }

    // ──────────────────────────── Mailbox delivery queries ───────────────────────

    /**
     * Returns all undelivered mailbox items for the given player.
     * Used by {@code PlayerJoinEvent} handler to deliver pending items.
     *
     * @param playerId the player's UUID
     * @return list of pending mailbox entries as [symbol, quantity, id] rows
     */
    public List<MailboxEntry> loadUndeliveredMailbox(UUID playerId) {
        List<MailboxEntry> entries = new ArrayList<>();
        String sql = "SELECT id, symbol, quantity FROM mailbox_items " +
                "WHERE player_id = ? AND delivered = 0 ORDER BY stored_at ASC";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new MailboxEntry(
                            rs.getLong("id"),
                            rs.getString("symbol"),
                            rs.getInt("quantity")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.severe("[BlockStreet] Failed to load mailbox for player "
                    + playerId + ": " + e.getMessage());
        }
        return entries;
    }

    /**
     * Marks a single mailbox entry as delivered (by row ID).
     * Called after items are successfully added to the player's inventory.
     *
     * @param rowId the {@code mailbox_items.id} primary key
     */
    public void markMailboxDelivered(long rowId) {
        String sql = "UPDATE mailbox_items SET delivered = 1 WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, rowId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[BlockStreet] Failed to mark mailbox entry " + rowId
                    + " as delivered: " + e.getMessage());
        }
    }

    // ──────────────────────────── Nested value type ───────────────────────────────

    /**
     * Lightweight value object for a mailbox row returned by {@link #loadUndeliveredMailbox}.
     *
     * @param rowId    primary key of the {@code mailbox_items} row
     * @param symbol   asset symbol
     * @param quantity number of items to deliver
     */
    public record MailboxEntry(long rowId, String symbol, int quantity) {}
}
