package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.db.DatabaseService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@code /bs history [page]}.
 *
 * <p>Fetches and displays the player's personal trade history from the {@code trade_history}
 * table in reverse-chronological order, paginated at 10 trades per page. The DB query runs
 * asynchronously to avoid blocking the Main Thread; the formatted message is sent back on
 * the Main Thread via {@code Bukkit.getScheduler().runTask()}.
 *
 * <h2>Thread Safety</h2>
 * The JDBC call runs on an async task. The chat send runs on the Main Thread.
 */
public final class HistoryCommand implements SubCommand {

    private static final int PAGE_SIZE = 10;
    private static final DecimalFormat PRICE_FMT = new DecimalFormat("#,##0.00");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("MM/dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final BlockStreet plugin;

    public HistoryCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName()    { return "history"; }

    @Override
    public String getUsage()   { return "/bs history [page]"; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[BlockStreet] This command can only be used by players.");
            return true;
        }

        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                player.sendMessage("§c[BlockStreet] Invalid page number.");
                return true;
            }
        }

        final int finalPage    = page;
        final int offset       = (page - 1) * PAGE_SIZE;
        final java.util.UUID playerUid = player.getUniqueId();
        final String playerUidStr      = playerUid.toString();

        player.sendMessage("§7[BlockStreet] Loading trade history…");

        // Run DB query off the main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines = new ArrayList<>();
            DatabaseService db = plugin.getDatabaseService();

            // Join to resting_orders to determine effective side for each trade
            String sql = """
                    SELECT th.symbol,
                           th.execution_price,
                           th.qty_filled,
                           th.taker_fee,
                           th.executed_at,
                           CASE WHEN th.taker_player_id = ? THEN 'TAKER' ELSE 'MAKER' END AS role,
                           ro_taker.side AS taker_side,
                           ro_maker.side AS maker_side
                    FROM trade_history th
                    LEFT JOIN resting_orders ro_taker ON ro_taker.order_id = th.taker_order_id
                    LEFT JOIN resting_orders ro_maker ON ro_maker.order_id = th.maker_order_id
                    WHERE th.maker_player_id = ? OR th.taker_player_id = ?
                    ORDER BY th.executed_at DESC
                    LIMIT ? OFFSET ?
                    """;

            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUidStr);
                ps.setString(2, playerUidStr);
                ps.setString(3, playerUidStr);
                ps.setInt(4, PAGE_SIZE);
                ps.setInt(5, offset);

                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        count++;
                        String symbol = rs.getString("symbol");
                        double price  = rs.getDouble("execution_price");
                        int    qty    = rs.getInt("qty_filled");
                        double fee    = rs.getDouble("taker_fee");
                        long   epoch  = rs.getLong("executed_at");
                        String role   = rs.getString("role");

                        // Determine effective side for this player
                        boolean isTaker = "TAKER".equals(role);
                        String takerSide = rs.getString("taker_side");
                        String makerSide = rs.getString("maker_side");
                        String effectiveSide = isTaker
                                ? (takerSide != null ? takerSide : "?")
                                : (makerSide != null ? makerSide : "?");

                        String sideColor = "BUY".equals(effectiveSide) ? "§a" : "§c";
                        String feeStr    = isTaker
                                ? String.format(" §7(fee: $%.2f)", fee)
                                : "";
                        String dateStr   = DATE_FMT.format(Instant.ofEpochMilli(epoch));

                        lines.add(String.format("§8%s §7│ %s%s §f%s§7×§e%s §7@ §e$%s%s",
                                dateStr, sideColor, effectiveSide, qty, symbol,
                                PRICE_FMT.format(price), feeStr));
                    }
                    if (count == 0) {
                        lines.add("§7No trades found" + (finalPage > 1 ? " on page " + finalPage : "") + ".");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[HistoryCommand] DB error: " + e.getMessage());
                lines.add("§c[BlockStreet] Failed to load trade history. Please try again.");
            }

            // Deliver to player on Main Thread
            final List<String> finalLines = lines;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerUid.equals(player.getUniqueId().toString())
                        ? player.getUniqueId() : java.util.UUID.randomUUID());
                if (p == null) return; // player disconnected

                p.sendMessage("§6§l[BlockStreet] §eYour Trade History §7(page " + finalPage + "):");
                p.sendMessage("§8──────────────────────────────────────────");
                for (String line : finalLines) {
                    p.sendMessage(line);
                }
                p.sendMessage("§8──────────────────────────────────────────");
                if (finalLines.size() == PAGE_SIZE) {
                    p.sendMessage("§7Next page: §e/bs history " + (finalPage + 1));
                }
            });
        });

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("<page>");
        return List.of();
    }
}
