package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.engine.book.OrderBook;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderStatus;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Handles {@code /bs cancel <orderId>}.
 *
 * <p>Looks up the order by its short-form prefix (first 8 chars of the UUID) or full UUID
 * across all books for this player. Routes the cancellation via
 * {@link com.perseusj.blockstreet.engine.OrderSubmissionService#cancelOrder(Order)} which
 * CAS-guards the consumed flag to prevent a race with an in-flight fill (edge case E12).
 *
 * <p><strong>Main Thread only.</strong>
 */
public final class CancelCommand implements SubCommand {

    private final BlockStreet plugin;

    public CancelCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName()    { return "cancel"; }

    @Override
    public String getUsage()   { return "/bs cancel <orderId>"; }

    @Override
    public String getPermission() { return "blockstreet.use"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[BlockStreet] This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§c[BlockStreet] Usage: " + getUsage());
            return true;
        }

        String idInput = args[0].toLowerCase();
        UUID   playerId = player.getUniqueId();

        // Search all books for a matching order belonging to this player
        Order target = null;
        outer:
        for (OrderBook book : plugin.getMatchingEngine().getAllBooks().values()) {
            for (Order order : book.getAllOrders()) {
                if (!order.getPlayerId().equals(playerId)) continue;
                // Accept full UUID or 8-char prefix
                String fullId  = order.getOrderId().toString().toLowerCase();
                String shortId = fullId.substring(0, 8);
                if (fullId.equals(idInput) || shortId.equals(idInput)) {
                    target = order;
                    break outer;
                }
            }
        }

        if (target == null) {
            player.sendMessage("§c[BlockStreet] No active order found with ID: §e" + idInput);
            player.sendMessage("§7Use §e/bs orders§7 to view your open orders.");
            return true;
        }

        if (target.getStatus() != OrderStatus.OPEN
                && target.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            player.sendMessage("§c[BlockStreet] Order §e" + idInput
                    + "§c is already §e" + target.getStatus() + "§c and cannot be cancelled.");
            return true;
        }

        boolean queued = plugin.getOrderSubmissionService().cancelOrder(target);
        if (queued) {
            player.sendMessage("§e[BlockStreet] §fCancellation queued for order §e"
                    + target.getOrderId().toString().substring(0, 8) + "…");
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            // Suggest short-IDs of the player's open orders
            UUID playerId = player.getUniqueId();
            List<String> suggestions = new java.util.ArrayList<>();
            for (OrderBook book : plugin.getMatchingEngine().getAllBooks().values()) {
                for (Order order : book.getAllOrders()) {
                    if (order.getPlayerId().equals(playerId)) {
                        suggestions.add(order.getOrderId().toString().substring(0, 8));
                    }
                }
            }
            return suggestions;
        }
        return List.of();
    }
}
