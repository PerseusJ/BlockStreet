package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.config.AssetConfig;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@code /bs orders [symbol]}.
 *
 * <p>Opens the {@link com.perseusj.blockstreet.gui.ActiveOrdersGui} for the player.
 * If the player omits the symbol and exactly one asset is configured, that is used
 * automatically. If multiple assets exist, the player is prompted to specify one.
 *
 * <p><strong>Main Thread only.</strong>
 */
public final class OrdersCommand implements SubCommand {

    private final BlockStreet plugin;

    public OrdersCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName()    { return "orders"; }

    @Override
    public String getUsage()   { return "/bs orders [symbol]"; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[BlockStreet] This command can only be used by players.");
            return true;
        }

        var assets = plugin.getConfigManager().getAllAssets();

        String symbol;
        if (args.length == 0) {
            if (assets.isEmpty()) {
                player.sendMessage("§c[BlockStreet] No tradeable assets configured.");
                return true;
            }
            if (assets.size() == 1) {
                symbol = assets.keySet().iterator().next();
            } else {
                player.sendMessage("§c[BlockStreet] Please specify a symbol: §e/bs orders <symbol>");
                player.sendMessage("§7Available: " + String.join(", ", assets.keySet()));
                return true;
            }
        } else {
            symbol = args[0].toUpperCase();
        }

        AssetConfig asset = plugin.getConfigManager().getAsset(symbol);
        if (asset == null || !asset.isEnabled()) {
            player.sendMessage("§c[BlockStreet] Unknown or disabled asset: §e" + symbol);
            return true;
        }

        plugin.getGuiManager().openGui(player);
        var session = plugin.getGuiManager().getSession(player.getUniqueId());
        if (session != null) {
            session.setActiveTab(com.perseusj.blockstreet.gui.GuiTab.MY_ORDERS);
            plugin.getGuiManager().renderSession(session);
        }
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
