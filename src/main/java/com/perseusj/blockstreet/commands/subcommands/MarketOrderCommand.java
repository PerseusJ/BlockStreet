package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.engine.model.OrderSide;
import com.perseusj.blockstreet.engine.model.OrderType;
import com.perseusj.blockstreet.gui.GuiTab;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles {@code /bs market buy <symbol> <qty>}, {@code /bs market sell <symbol> <qty>},
 * and {@code /bs market open [tab]}.
 *
 * <p>The {@code open} sub-action is fired by the Market Ledger book's clickable text buttons:
 * <ul>
 *   <li>{@code /bs market open BROWSE} — Opens the GUI at the Browse tab (Tab 1).</li>
 *   <li>{@code /bs market open MAILBOX} — Opens the GUI at the Mailbox tab (Tab 5).</li>
 * </ul>
 * Any other tab name is also accepted; invalid names silently fall back to {@link GuiTab#BROWSE}.
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
    public String getUsage()   { return "/bs market <buy|sell|open> ..."; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[BlockStreet] This command can only be used by players.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("§c[BlockStreet] Usage: " + getUsage());
            return true;
        }

        // ── Handle 'open' sub-action (fired by the Market Ledger book click events) ──
        if (args[0].equalsIgnoreCase("open")) {
            GuiTab tab = GuiTab.BROWSE; // default
            if (args.length >= 2) {
                try {
                    tab = GuiTab.valueOf(args[1].toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    // Unknown tab name → silently fall back to BROWSE
                }
            }
            var guiManager = plugin.getGuiManager();
            if (guiManager != null) {
                guiManager.openGui(player, tab);
            } else {
                player.sendMessage("§c[BlockStreet] GUI is not available right now.");
            }
            return true;
        }

        // ── Handle 'buy' / 'sell' market orders ─────────────────────────────────────
        if (args.length < 3) {
            player.sendMessage("§c[BlockStreet] Usage: /bs market <buy|sell> <symbol> <qty>");
            return true;
        }

        OrderSide side;
        try {
            side = OrderSide.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage("§c[BlockStreet] Unknown action '" + args[0]
                    + "'. Use §ebuy§c, §esell§c, or §eopen§c.");
            return true;
        }

        String symbol = args[1].toUpperCase();
        int    qty;
        try {
            qty = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§c[BlockStreet] Invalid quantity. Usage: /bs market <buy|sell> <symbol> <qty>");
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
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> options = new ArrayList<>(List.of("buy", "sell", "open"));
            return options.stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("open")) {
                // Offer GuiTab enum values for /bs market open <tab>
                String partial = args[1].toUpperCase();
                return Arrays.stream(GuiTab.values())
                        .map(Enum::name)
                        .filter(name -> name.startsWith(partial))
                        .collect(Collectors.toList());
            }
            // For buy/sell: offer asset symbols
            List<String> completions = new ArrayList<>();
            String partial = args[1].toUpperCase();
            for (String sym : plugin.getConfigManager().getAllAssets().keySet()) {
                if (sym.startsWith(partial)) completions.add(sym);
            }
            return completions;
        }
        if (args.length == 3 && !args[0].equalsIgnoreCase("open")) {
            return List.of("<qty>");
        }
        return List.of();
    }
}
