package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.db.DatabaseService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

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

public final class LedgerCommand implements SubCommand {

    private static final DecimalFormat PRICE_FMT = new DecimalFormat("#,##0.00");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("MM/dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final int TRADES_PER_PAGE = 7; // About 7 trades fit on a book page

    private final BlockStreet plugin;

    public LedgerCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() { return "ledger"; }

    @Override
    public String getUsage() { return "/bs ledger"; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[BlockStreet] This command can only be used by players.");
            return true;
        }

        final java.util.UUID playerUid = player.getUniqueId();
        final String playerUidStr = playerUid.toString();

        player.sendMessage("§7[BlockStreet] Loading Market Ledger...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> pages = new ArrayList<>();
            DatabaseService db = plugin.getDatabaseService();

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
                    LIMIT 50
                    """;

            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUidStr);
                ps.setString(2, playerUidStr);
                ps.setString(3, playerUidStr);

                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder currentPage = new StringBuilder("§lMarket Ledger\n\n");
                    int count = 0;
                    
                    while (rs.next()) {
                        String symbol = rs.getString("symbol");
                        double price = rs.getDouble("execution_price");
                        int qty = rs.getInt("qty_filled");
                        double fee = rs.getDouble("taker_fee");
                        long epoch = rs.getLong("executed_at");
                        String role = rs.getString("role");

                        boolean isTaker = "TAKER".equals(role);
                        String takerSide = rs.getString("taker_side");
                        String makerSide = rs.getString("maker_side");
                        String effectiveSide = isTaker ? (takerSide != null ? takerSide : "?") : (makerSide != null ? makerSide : "?");
                        
                        String sideColor = "BUY".equals(effectiveSide) ? "§2" : "§4";
                        String feeStr = isTaker ? String.format(" fee: $%.2f", fee) : "";
                        String dateStr = DATE_FMT.format(Instant.ofEpochMilli(epoch));

                        String line = String.format("§8%s\n%s%s §0%dx %s\n@ $%s%s\n",
                                dateStr, sideColor, effectiveSide, qty, symbol,
                                PRICE_FMT.format(price), feeStr);

                        currentPage.append(line).append("\n");
                        count++;

                        if (count % TRADES_PER_PAGE == 0) {
                            pages.add(currentPage.toString());
                            currentPage = new StringBuilder();
                        }
                    }

                    if (count == 0) {
                        pages.add("§lMarket Ledger\n\n§8No trades found.");
                    } else if (currentPage.length() > 0) {
                        pages.add(currentPage.toString());
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[LedgerCommand] DB error: " + e.getMessage());
                pages.add("§cError loading ledger.");
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerUid);
                if (p == null || !p.isOnline()) return;

                ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
                BookMeta meta = (BookMeta) book.getItemMeta();
                if (meta != null) {
                    meta.setTitle("Market Ledger");
                    meta.setAuthor("BlockStreet");
                    for (String pageText : pages) {
                        meta.addPage(pageText);
                    }
                    book.setItemMeta(meta);
                }
                
                p.openBook(book);
            });
        });

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
