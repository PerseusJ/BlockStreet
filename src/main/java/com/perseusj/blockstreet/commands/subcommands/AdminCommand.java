package com.perseusj.blockstreet.commands.subcommands;

import com.perseusj.blockstreet.BlockStreet;
import com.perseusj.blockstreet.commands.SubCommand;
import com.perseusj.blockstreet.config.AssetConfig;
import com.perseusj.blockstreet.engine.book.OrderBook;
import com.perseusj.blockstreet.engine.model.Order;
import com.perseusj.blockstreet.engine.model.OrderStatus;
import com.perseusj.blockstreet.utils.ItemFactory;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Handles all {@code /bs admin <sub>} commands.
 *
 * <h2>Sub-commands</h2>
 * <ul>
 *   <li>{@code /bs admin reload} — hot-reload config.yml; propagates new taker fee rate
 *       to the matching engine.</li>
 *   <li>{@code /bs admin wipe <symbol>} — cancels all resting orders for a symbol and
 *       removes the book. Items/currency are refunded to each owner.</li>
 *   <li>{@code /bs admin release <player>} — emergency refund of all resting orders for
 *       a specific player across all books (edge case E20).</li>
 *   <li>{@code /bs admin setprice <symbol> <price>} — injects a reference price into the
 *       book's lastTradePrice stat (no order is executed). Informational only.</li>
 * </ul>
 *
 * <p><strong>Requires {@code blockstreet.admin} permission.</strong>
 * <p><strong>Main Thread only.</strong>
 */
public final class AdminCommand implements SubCommand {

    private final BlockStreet plugin;

    public AdminCommand(BlockStreet plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName()       { return "admin"; }

    @Override
    public String getUsage()      {
        return "/bs admin <reload|wipe <symbol>|release <player>|additem <symbol>|setprice <symbol> <price>>";
    }

    @Override
    public String getPermission() { return "blockstreet.admin"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockstreet.admin")) {
            sender.sendMessage("§c[BlockStreet] You don't have permission to use admin commands.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§c[BlockStreet] Usage: " + getUsage());
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload"   -> handleReload(sender);
            case "wipe"     -> handleWipe(sender, args);
            case "release"  -> handleRelease(sender, args);
            case "additem"  -> handleAddItem(sender, args);
            case "setprice" -> handleSetPrice(sender, args);
            default -> {
                sender.sendMessage("§c[BlockStreet] Unknown admin sub-command: §e" + args[0]);
                sender.sendMessage("§7Usage: " + getUsage());
                yield true;
            }
        };
    }

    // ─────────────────────────── reload ──────────────────────────────────────────

    private boolean handleReload(CommandSender sender) {
        plugin.getConfigManager().load();
        // Propagate updated taker fee rate to the engine
        plugin.getMatchingEngine().setTakerFeeRate(plugin.getConfigManager().getTakerFeeRate());
        // Refresh ItemFactory registry in case assets changed
        ItemFactory.clearRegistry();
        for (AssetConfig asset : plugin.getConfigManager().getAllAssets().values()) {
            ItemFactory.register(asset.getSymbol(), asset.getMaterial());
        }
        sender.sendMessage("§a[BlockStreet] §fConfiguration reloaded successfully.");
        sender.sendMessage(String.format("  §7Taker fee: §e%.2f%%  §7Assets: §e%d",
                plugin.getConfigManager().getTakerFeeRate() * 100,
                plugin.getConfigManager().getAllAssets().size()));
        plugin.getLogger().info("[BlockStreet] Config reloaded by " + sender.getName());
        return true;
    }

    // ─────────────────────────── wipe ────────────────────────────────────────────

    /**
     * Cancels all resting orders for the specified symbol and removes the order book.
     * Each affected player receives a refund via SettlementDispatcher (cancellation path).
     */
    private boolean handleWipe(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[BlockStreet] Usage: /bs admin wipe <symbol>");
            return true;
        }
        String symbol = args[1].toUpperCase();
        OrderBook book = plugin.getMatchingEngine().getBook(symbol);
        if (book == null) {
            sender.sendMessage("§c[BlockStreet] No active order book for symbol: §e" + symbol);
            return true;
        }

        List<Order> resting = book.getAllOrders();
        int cancelledCount = 0;
        for (Order order : resting) {
            if (order.getStatus() == OrderStatus.OPEN
                    || order.getStatus() == OrderStatus.PARTIALLY_FILLED) {
                plugin.getOrderSubmissionService().cancelOrder(order);
                cancelledCount++;
            }
        }

        sender.sendMessage(String.format(
                "§a[BlockStreet] §fWipe queued for §e%s§f. §e%d§f resting order(s) cancelled.",
                symbol, cancelledCount));
        plugin.getLogger().warning("[BlockStreet] Admin wipe of " + symbol
                + " by " + sender.getName() + " — " + cancelledCount + " orders cancelled.");
        return true;
    }

    // ─────────────────────────── release ─────────────────────────────────────────

    /**
     * Edge case E20: Admin-triggered refund + cancel of all resting orders for a specific
     * player across all books. Used when a player is banned while holding locked assets.
     */
    private boolean handleRelease(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[BlockStreet] Usage: /bs admin release <playerName>");
            return true;
        }
        String targetName = args[1];
        // Look up UUID from online or offline player
        org.bukkit.OfflinePlayer offlineTarget = org.bukkit.Bukkit.getOfflinePlayer(targetName);
        if (offlineTarget == null || offlineTarget.getUniqueId() == null) {
            sender.sendMessage("§c[BlockStreet] Player not found: §e" + targetName);
            return true;
        }
        java.util.UUID targetId = offlineTarget.getUniqueId();

        int released = 0;
        for (OrderBook book : plugin.getMatchingEngine().getAllBooks().values()) {
            for (Order order : book.getAllOrders()) {
                if (order.getPlayerId().equals(targetId)
                        && (order.getStatus() == OrderStatus.OPEN
                            || order.getStatus() == OrderStatus.PARTIALLY_FILLED)) {
                    plugin.getOrderSubmissionService().cancelOrder(order);
                    released++;
                }
            }
        }

        sender.sendMessage(String.format(
                "§a[BlockStreet] §fReleased §e%d§f order(s) for player §e%s§f.",
                released, targetName));
        plugin.getLogger().warning("[BlockStreet] Admin release of " + targetName
                + " by " + sender.getName() + " — " + released + " order(s) cancelled.");
        return true;
    }

    // ─────────────────────────── additem ─────────────────────────────────────────

    /**
     * Blueprint §5.1 — {@code /bs admin additem <symbol>}: dynamically registers a new
     * tradeable symbol at runtime.
     *
     * <p>The symbol is resolved via {@link Material#matchMaterial(String)} (the same
     * mechanism {@link com.perseusj.blockstreet.config.ConfigManager} uses in §5.3).
     * If valid and an item, it is injected into {@link ItemFactory} and the config is
     * reloaded to pick up any matching {@code assets:<symbol>} section.
     *
     * <p><strong>Note:</strong> For the asset to persist across restarts, the admin must
     * also add the corresponding {@code assets:} entry to {@code config.yml} manually.
     */
    private boolean handleAddItem(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[BlockStreet] Usage: /bs admin additem <symbol>");
            return true;
        }
        String symbol = args[1].toUpperCase();

        // Already registered?
        if (plugin.getConfigManager().getAsset(symbol) != null) {
            sender.sendMessage("§e[BlockStreet] §fAsset §e" + symbol
                    + "§f is already registered. Use §e/bs admin reload§f to refresh.");
            return true;
        }

        // Validate via Material.matchMaterial — same method used by ConfigManager §5.3
        Material mat = Material.matchMaterial(symbol);
        if (mat == null) {
            sender.sendMessage("§c[BlockStreet] Unknown material: §e" + symbol
                    + "§c. Must be a valid Bukkit Material name (e.g. IRON_INGOT).");
            return true;
        }
        if (!mat.isItem()) {
            sender.sendMessage("§c[BlockStreet] §e" + symbol
                    + "§c is not a valid item material (block-only materials cannot be traded).");
            return true;
        }

        // Register in ItemFactory so deliveries work immediately
        ItemFactory.register(symbol, mat);

        // Hot-reload config to pull in any config.yml section for this symbol
        plugin.getConfigManager().load();

        if (plugin.getConfigManager().getAsset(symbol) != null) {
            sender.sendMessage("§a[BlockStreet] §fAsset §e" + symbol
                    + "§f registered and loaded from config.yml successfully.");
        } else {
            sender.sendMessage("§e[BlockStreet] §fMaterial §e" + symbol
                    + "§f registered in ItemFactory, but no §eassets:" + symbol
                    + "§f entry found in config.yml.");
            sender.sendMessage("§7Add a config.yml entry for full min/max price and tick support.");
        }
        plugin.getLogger().info("[BlockStreet] Admin additem '" + symbol
                + "' (" + mat + ") registered by " + sender.getName());
        return true;
    }

    // ─────────────────────────── setprice ─────────────────────────────────────────


    /**
     * Injects a reference last-trade price for display purposes only. Does not execute
     * any order or modify the book structure.
     */
    private boolean handleSetPrice(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c[BlockStreet] Usage: /bs admin setprice <symbol> <price>");
            return true;
        }
        String symbol = args[1].toUpperCase();
        double price;
        try {
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c[BlockStreet] Invalid price: §e" + args[2]);
            return true;
        }
        if (price <= 0) {
            sender.sendMessage("§c[BlockStreet] Price must be positive.");
            return true;
        }

        AssetConfig asset = plugin.getConfigManager().getAsset(symbol);
        if (asset == null || !asset.isEnabled()) {
            sender.sendMessage("§c[BlockStreet] Unknown or disabled asset: §e" + symbol);
            return true;
        }

        OrderBook book = plugin.getMatchingEngine().getBook(symbol);
        if (book == null) {
            sender.sendMessage("§e[BlockStreet] §fNo book exists for §e" + symbol
                    + "§f yet. Price will take effect once the first order is placed.");
            // We can't set the price without a book. Inform admin.
            return true;
        }

        // Inject via recordTrade with qty=0 (no volume impact, just sets lastTradePrice)
        book.recordTrade(price, 0);
        book.rebuildDepthSnapshot(); // refresh the snapshot so GUI picks up new lastTradePrice
        sender.sendMessage(String.format(
                "§a[BlockStreet] §fReference price for §e%s§f set to §e$%.2f§f.",
                symbol, price));
        plugin.getLogger().info("[BlockStreet] Admin setprice " + symbol
                + "=" + price + " by " + sender.getName());
        return true;
    }

    // ─────────────────────────── Tab completion ───────────────────────────────────

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockstreet.admin")) return List.of();

        if (args.length == 1) {
            return filterPrefix(args[0], List.of("reload", "wipe", "release", "additem", "setprice"));
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "wipe", "setprice" -> {
                    List<String> syms = new ArrayList<>();
                    String partial = args[1].toUpperCase();
                    for (String sym : plugin.getConfigManager().getAllAssets().keySet()) {
                        if (sym.startsWith(partial)) syms.add(sym);
                    }
                    yield syms;
                }
                case "additem"  -> List.of("<symbol (e.g. IRON_INGOT)>");
                case "release"  -> List.of("<playerName>");
                default         -> List.of();
            };
        }
        if (args.length == 3 && "setprice".equalsIgnoreCase(args[0])) {
            return List.of("<price>");
        }
        return List.of();
    }

    private List<String> filterPrefix(String partial, List<String> options) {
        List<String> result = new ArrayList<>();
        for (String opt : options) {
            if (opt.startsWith(partial.toLowerCase())) result.add(opt);
        }
        return result;
    }
}
