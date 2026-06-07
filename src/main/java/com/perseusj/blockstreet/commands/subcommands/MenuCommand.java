package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.config.AssetConfig;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@code /bs menu [symbol]}.
 *
 * <p>Opens the main {@link com.perseusj.blockstreet.gui.MarketGui} for the specified asset
 * symbol. If no symbol is given and only one asset is configured, it opens that one
 * automatically. If multiple assets exist and no symbol was provided, the player receives
 * a list of valid symbols.
 *
 * <p><strong>Main Thread only.</strong>
 */
public final class MenuCommand implements SubCommand {

    private final BlockStreet plugin;

    public MenuCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName()    { return "menu"; }

    @Override
    public String getUsage()   { return "/bs menu [symbol]"; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[BlockStreet] This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            // Auto-open if exactly one asset, otherwise list assets
            var assets = plugin.getConfigManager().getAllAssets();
            if (assets.isEmpty()) {
                player.sendMessage("§c[BlockStreet] No tradeable assets are configured.");
                return true;
            }
            if (assets.size() == 1) {
                String symbol = assets.keySet().iterator().next();
                plugin.getGuiManager().openMarketGui(player, symbol);
                return true;
            }
            // Multiple assets — list them
            player.sendMessage("§6[BlockStreet] §fAvailable assets:");
            for (AssetConfig asset : assets.values()) {
                player.sendMessage("  §e" + asset.getSymbol()
                        + " §7— " + asset.getDisplayName());
            }
            player.sendMessage("§7Use §e/bs menu <symbol>§7 to open the market.");
            return true;
        }

        String symbol = args[0].toUpperCase();
        plugin.getGuiManager().openMarketGui(player, symbol);
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
