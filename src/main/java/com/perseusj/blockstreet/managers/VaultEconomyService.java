package com.perseusj.blockstreet.managers;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Thin wrapper around Vault's {@link Economy} interface that:
 * <ul>
 *   <li>Centralises all Vault calls so the rest of the plugin never imports Vault directly.</li>
 *   <li>Translates {@link EconomyResponse} results into boolean success/failure and logs
 *       failures at WARNING level.</li>
 *   <li>Provides strongly-typed overloads for both online ({@link Player}) and offline
 *       ({@link UUID}) players.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * All Vault economy operations <strong>must</strong> be called from the <strong>Server Main Thread</strong>.
 * This class does not enforce that — callers (primarily {@link com.perseusj.blockstreet.engine.SettlementDispatcher})
 * are responsible for scheduling via {@code Bukkit.getScheduler().runTask()}.
 */
public final class VaultEconomyService {

    private final Economy economy;
    private final Logger  logger;

    public VaultEconomyService(Economy economy, Logger logger) {
        this.economy = economy;
        this.logger  = logger;
    }

    // ──────────────────────────── Balance queries ────────────────────────────────

    /**
     * Returns the current balance for the given player (online or offline).
     *
     * @param playerId the player's UUID
     * @return account balance, or {@code 0.0} if the account does not exist
     */
    public double getBalance(UUID playerId) {
        OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(playerId);
        return economy.getBalance(op);
    }

    /**
     * Returns {@code true} if the player has at least {@code amount} available.
     */
    public boolean has(UUID playerId, double amount) {
        OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(playerId);
        return economy.has(op, amount);
    }

    // ──────────────────────────── Withdrawals ────────────────────────────────────

    /**
     * Withdraws {@code amount} from the player's account.
     *
     * @param playerId target player
     * @param amount   positive amount to withdraw
     * @return {@code true} if the withdrawal succeeded
     */
    public boolean withdrawPlayer(UUID playerId, double amount) {
        if (amount <= 0) {
            logger.warning("[VaultEconomyService] Attempted to withdraw non-positive amount: " + amount);
            return false;
        }
        OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(playerId);
        EconomyResponse resp = economy.withdrawPlayer(op, amount);
        if (!resp.transactionSuccess()) {
            logger.warning("[VaultEconomyService] Withdraw failed for " + playerId +
                    " amount=" + amount + " reason=" + resp.errorMessage);
        }
        return resp.transactionSuccess();
    }

    // ──────────────────────────── Deposits ───────────────────────────────────────

    /**
     * Deposits {@code amount} into the player's account.
     * Works for offline players — Vault's OfflinePlayer API handles the storage.
     *
     * @param playerId target player
     * @param amount   positive amount to deposit
     * @return {@code true} if the deposit succeeded
     */
    public boolean depositPlayer(UUID playerId, double amount) {
        if (amount <= 0) {
            // Epsilon-guard: avoid no-op deposit calls for sub-cent refunds
            return true;
        }
        OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(playerId);
        EconomyResponse resp = economy.depositPlayer(op, amount);
        if (!resp.transactionSuccess()) {
            logger.warning("[VaultEconomyService] Deposit failed for " + playerId +
                    " amount=" + amount + " reason=" + resp.errorMessage);
        }
        return resp.transactionSuccess();
    }

    // ──────────────────────────── Formatting ─────────────────────────────────────

    /**
     * Formats a currency amount using Vault's native formatter (e.g. "$1,234.56").
     */
    public String format(double amount) {
        return economy.format(amount);
    }
}
