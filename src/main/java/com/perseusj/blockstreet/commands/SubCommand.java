package com.perseusj.blockstreet.commands;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * Contract for all BlockStreet sub-command handlers.
 *
 * <p>Each implementor handles one sub-command (e.g. {@code /bs buy}, {@code /bs cancel}).
 * The {@link BlockStreetCommand} dispatcher calls {@link #execute} after matching the first
 * argument token to the value returned by {@link #getName()}.
 *
 * <h2>Thread Safety</h2>
 * All methods are called on the <strong>Server Main Thread</strong> unless explicitly
 * documented otherwise.
 */
public interface SubCommand {

    /**
     * The primary name of this sub-command (lower-case, no spaces).
     * Used for dispatch matching and the permission node suffix.
     *
     * @return e.g. {@code "buy"}, {@code "cancel"}, {@code "admin"}
     */
    String getName();

    /**
     * Executes this sub-command.
     *
     * @param sender the command sender (player or console)
     * @param args   the remaining arguments <em>after</em> the sub-command token;
     *               may be empty but never {@code null}
     * @return {@code true} if the command was handled (even if it failed with a user-facing message);
     *         {@code false} to let Bukkit print the usage string
     */
    boolean execute(CommandSender sender, String[] args);

    /**
     * Returns tab-completion suggestions for the current input state.
     *
     * @param sender the command sender
     * @param args   the arguments <em>after</em> the sub-command token (last arg is the partial)
     * @return a list of suggestions (may be empty); never {@code null}
     */
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }

    /**
     * The Bukkit permission node required to execute this sub-command.
     * Return {@code null} if no permission check should be performed.
     *
     * @return e.g. {@code "blockstreet.use"} or {@code "blockstreet.admin"}
     */
    default String getPermission() {
        return "blockstreet.use";
    }

    /**
     * Returns a brief usage hint shown when the command is mis-used.
     *
     * @return e.g. {@code "/bs buy <symbol> <qty> <price>"}
     */
    String getUsage();
}
