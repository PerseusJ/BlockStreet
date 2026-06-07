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
 * Handles {@code /bs buy <symbol> <qty> <price>} — CLI shortcut for a LIMIT buy order.
 *
 * <p>This is the CLI equivalent of clicking "PLACE BUY ORDER" in the GUI and entering
 * values via chat. It executes Phase 2A (asset lock) on the Main Thread and submits
 * the order to the engine.
 *
 * <p><strong>Main Thread only.</strong>
 */
public final class BuyCommand implements SubCommand {

    private final BlockStreet plugin;

    public BuyCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName()    { return "buy"; }

    @Override
    public String getUsage()   { return "/bs buy <symbol> <qty> <price>"; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[BlockStreet] This command can only be used by players.");
            return true;
        }

        if (args.length < 3) {
            player.sendMessage("§c[BlockStreet] Usage: " + getUsage());
            return true;
        }

        String symbol = args[0].toUpperCase();
        int    qty;
        double price;

        try {
            qty   = Integer.parseInt(args[1]);
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§c[BlockStreet] Invalid number format. Usage: " + getUsage());
            return true;
        }

        if (qty <= 0) {
            player.sendMessage("§c[BlockStreet] Quantity must be greater than zero.");
            return true;
        }
        if (price <= 0) {
            player.sendMessage("§c[BlockStreet] Price must be greater than zero.");
            return true;
        }

        plugin.getOrderSubmissionService().submitOrder(
                player, symbol, OrderSide.BUY, OrderType.LIMIT, price, qty);
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
        if (args.length == 2) return List.of("<qty>");
        if (args.length == 3) return List.of("<price>");
        return List.of();
    }
}
