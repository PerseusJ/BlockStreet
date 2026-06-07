package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.engine.book.MarketDepthSnapshot;
import com.perseusj.blockstreet.engine.book.OrderBook;
import org.bukkit.command.CommandSender;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@code /bs price <symbol>}.
 *
 * <p>Reads the {@link MarketDepthSnapshot} from the order book (volatile read, safe on
 * any thread) and prints the last trade price, best bid, best ask and 24h volume to
 * the sender's chat. No Bukkit-unsafe API calls are made.
 *
 * <p><strong>Main Thread only</strong> (called from command executor which runs on Main Thread).
 */
public final class PriceCommand implements SubCommand {

    private static final DecimalFormat PRICE_FMT  = new DecimalFormat("#,##0.00");
    private static final DecimalFormat VOL_FMT    = new DecimalFormat("#,##0");

    private final BlockStreet plugin;

    public PriceCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName()    { return "price"; }

    @Override
    public String getUsage()   { return "/bs price <symbol>"; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§c[BlockStreet] Usage: " + getUsage());
            return true;
        }

        String symbol = args[0].toUpperCase();
        AssetConfig asset = plugin.getConfigManager().getAsset(symbol);
        if (asset == null || !asset.isEnabled()) {
            sender.sendMessage("§c[BlockStreet] Unknown or disabled asset: §e" + symbol);
            return true;
        }

        OrderBook book = plugin.getMatchingEngine().getBook(symbol);
        if (book == null) {
            sender.sendMessage("§e[BlockStreet] §fNo market data yet for §e" + symbol
                    + "§f — no orders have been placed.");
            return true;
        }

        MarketDepthSnapshot snap = book.getDepthSnapshot();
        double lastPrice = book.getLastTradePrice();
        long   vol24h    = book.getTotalVolume24h();

        String lastStr = lastPrice > 0.0
                ? "§e$" + PRICE_FMT.format(lastPrice)
                : "§7N/A";

        String bidStr = snap.bids().isEmpty()
                ? "§7N/A"
                : "§a$" + PRICE_FMT.format(snap.bids().get(0).price());

        String askStr = snap.asks().isEmpty()
                ? "§7N/A"
                : "§c$" + PRICE_FMT.format(snap.asks().get(0).price());

        String spreadStr;
        if (!snap.bids().isEmpty() && !snap.asks().isEmpty()) {
            double spread = snap.asks().get(0).price() - snap.bids().get(0).price();
            spreadStr = "§e$" + PRICE_FMT.format(spread);
        } else {
            spreadStr = "§7N/A";
        }

        String currencySym = plugin.getConfigManager().getCurrencySymbol();

        sender.sendMessage("§6§l[BlockStreet] §e" + asset.getDisplayName() + " §7(" + symbol + ")");
        sender.sendMessage("  §7Last Price : " + lastStr);
        sender.sendMessage("  §7Best Bid   : " + bidStr);
        sender.sendMessage("  §7Best Ask   : " + askStr);
        sender.sendMessage("  §7Spread     : " + spreadStr);
        sender.sendMessage("  §7Volume 24h : §b" + VOL_FMT.format(vol24h) + " units");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String partial = args[0].toUpperCase();
            for (String sym : plugin.getConfigManager().getAllAssets().keySet()) {
                if (sym.startsWith(partial)) completions.add(sym);
            }
            return completions;
        }
        return List.of();
    }
}
