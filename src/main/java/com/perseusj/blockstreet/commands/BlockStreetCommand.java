package com.perseusj.blockstreet.commands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.subcommands.*;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root command executor for {@code /blockstreet} (aliases: {@code /bs}, {@code /bse},
 * {@code /market}).
 *
 * <h2>Dispatch Strategy</h2>
 * All sub-commands are registered in a {@code LinkedHashMap} keyed by their lower-case name.
 * The first argument of the command is matched against this map; the matching
 * {@link SubCommand} receives the remaining arguments.
 *
 * <h2>Permissions</h2>
 * Each {@link SubCommand} declares its required permission via {@link SubCommand#getPermission()}.
 * This class checks it before delegating. No permission → rejection message, no sub-command
 * execution.
 *
 * <h2>Thread Safety</h2>
 * All methods are called on the <strong>Server Main Thread</strong> by Bukkit.
 */
public final class BlockStreetCommand implements CommandExecutor, TabCompleter {

    /** Ordered map of sub-command name → handler. */
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();

    private final BlockStreet plugin;

    // ──────────────────────────── Constructor ────────────────────────────────────

    public BlockStreetCommand(BlockStreet plugin) {
        this.plugin = plugin;

        // Register all sub-commands in the logical order they appear in the plan
        register(new MenuCommand(plugin));
        register(new BuyCommand(plugin));
        register(new SellCommand(plugin));
        register(new MarketOrderCommand(plugin));
        register(new CancelCommand(plugin));
        register(new OrdersCommand(plugin));
        register(new HistoryCommand(plugin));
        register(new PriceCommand(plugin));
        register(new AdminCommand(plugin));
    }

    private void register(SubCommand sub) {
        subCommands.put(sub.getName().toLowerCase(), sub);
    }

    // ──────────────────────────── CommandExecutor ────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        // No arguments — print help
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subName = args[0].toLowerCase();

        // Special alias: "/bs help"
        if ("help".equals(subName)) {
            sendHelp(sender);
            return true;
        }

        SubCommand sub = subCommands.get(subName);
        if (sub == null) {
            sender.sendMessage("§c[BlockStreet] Unknown command: §e" + subName
                    + "§c. Type §e/bs help§c for a list.");
            return true;
        }

        // Permission check
        String perm = sub.getPermission();
        if (perm != null && !sender.hasPermission(perm)) {
            sender.sendMessage("§c[BlockStreet] You don't have permission to use this command.");
            return true;
        }

        // Delegate with the args tail (everything after the sub-command token)
        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);
        return sub.execute(sender, subArgs);
    }

    // ──────────────────────────── TabCompleter ───────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {

        if (args.length == 1) {
            // Offer sub-command names that the sender has permission for
            List<String> completions = new ArrayList<>();
            String partial = args[0].toLowerCase();
            for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
                if (!entry.getKey().startsWith(partial)) continue;
                String perm = entry.getValue().getPermission();
                if (perm == null || sender.hasPermission(perm)) {
                    completions.add(entry.getKey());
                }
            }
            return completions;
        }

        // Delegate tab completion to the matching sub-command
        if (args.length >= 2) {
            SubCommand sub = subCommands.get(args[0].toLowerCase());
            if (sub != null) {
                String perm = sub.getPermission();
                if (perm == null || sender.hasPermission(perm)) {
                    String[] subArgs = new String[args.length - 1];
                    System.arraycopy(args, 1, subArgs, 0, subArgs.length);
                    return sub.tabComplete(sender, subArgs);
                }
            }
        }

        return List.of();
    }

    // ──────────────────────────── Help text ──────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD
                + "══ BlockStreet Commands ══");
        for (SubCommand sub : subCommands.values()) {
            String perm = sub.getPermission();
            if (perm != null && !sender.hasPermission(perm)) continue;
            sender.sendMessage(ChatColor.YELLOW + sub.getUsage()
                    + ChatColor.GRAY + "  (" + perm + ")");
        }
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD
                + "══════════════════════════");
    }
}
