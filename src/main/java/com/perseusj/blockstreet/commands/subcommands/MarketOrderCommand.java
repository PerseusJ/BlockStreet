package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@code /bs market buy <symbol> <qty>} and {@code /bs market sell <symbol> <qty>}.
 *
 * <p>Routes to the {@link com.perseusj.blockstreet.engine.OrderSubmissionService} with
 * {@link OrderType#MARKET}. The sweep cost is estimated from the current depth snapshot
 * and withdrawn as escrow before the order enters the engine (Phase 2A).
 *
 * <p><strong>Main Thread only.</strong>
 */
public final class MarketOrderCommand implements SubCommand {

    private final BlockStreet plugin;

    public MarketOrderCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName()    { return "market"; }

    @Override
    public String getUsage()   { return "/bs market <buy|sell> <symbol> <qty>"; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[BlockStreet] This command can only be used by players.");
            return true;
        }

        // args[0] = buy|sell, args[1] = symbol, args[2] = qty
        if (args.length < 3) {
            player.sendMessage("§c[BlockStreet] Usage: " + getUsage());
            return true;
        }

        OrderSide side;
        try {
            side = OrderSide.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage("§c[BlockStreet] Unknown side '" + args[0]
                    + "'. Use §ebuy§c or §esell§c.");
            return true;
        }

        String symbol = args[1].toUpperCase();
        int    qty;
        try {
            qty = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§c[BlockStreet] Invalid quantity. Usage: " + getUsage());
            return true;
        }

        if (qty <= 0) {
            player.sendMessage("§c[BlockStreet] Quantity must be greater than zero.");
            return true;
        }

        // price = 0.0 signals a MARKET order to OrderSubmissionService
        plugin.getOrderSubmissionService().submitOrder(
                player, symbol, side, OrderType.MARKET, 0.0, qty);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("buy", "sell");
        if (args.length == 2) {
            List<String> completions = new ArrayList<>();
            String partial = args[1].toUpperCase();
            for (String sym : plugin.getConfigManager().getAllAssets().keySet()) {
                if (sym.startsWith(partial)) completions.add(sym);
            }
            return completions;
        }
        if (args.length == 3) return List.of("<qty>");
        return List.of();
    }
}
