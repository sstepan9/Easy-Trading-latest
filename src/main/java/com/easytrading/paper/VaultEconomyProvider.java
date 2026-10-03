package com.easytrading.paper;

import com.easytrading.paper.data.EconomyData;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class VaultEconomyProvider implements Economy {

    private final EasyTradingPlugin plugin;

    public VaultEconomyProvider(EasyTradingPlugin plugin) {
        this.plugin = plugin;
    }

    private EconomyData eco() {
        return plugin.getEconomy();
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "EasyTrading";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        Long normalized = normalizeAmount(amount);
        return normalized == null ? String.valueOf(amount) : Long.toString(normalized);
    }

    @Override
    public String currencyNamePlural() {
        return plugin.tr("vault.currency.plural");
    }

    @Override
    public String currencyNameSingular() {
        return plugin.tr("vault.currency.singular");
    }

    // ── OfflinePlayer-based methods (primary) ──

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return player == null ? 0D : eco().get(player.getUniqueId());
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        Long normalized = normalizeAmount(amount);
        return player != null && normalized != null && getBalance(player) >= normalized;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) return failure(0D, "vault.error.invalid_player");
        Long normalized = normalizeAmount(amount);
        double balance = getBalance(player);
        if (normalized == null) {
            return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE,
                    plugin.tr("vault.error.invalid_amount"));
        }

        EconomyData.MutationResult result = eco().tryWithdraw(player.getUniqueId(), normalized);
        if (result != EconomyData.MutationResult.SUCCESS) {
            String message = result == EconomyData.MutationResult.INSUFFICIENT_FUNDS
                    ? plugin.tr("vault.error.insufficient_funds")
                    : plugin.tr("vault.error.invalid_amount");
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, message);
        }
        return new EconomyResponse(normalized, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) return failure(0D, "vault.error.invalid_player");
        Long normalized = normalizeAmount(amount);
        double balance = getBalance(player);
        if (normalized == null) {
            return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE,
                    plugin.tr("vault.error.invalid_amount"));
        }

        EconomyData.MutationResult result = eco().tryDeposit(player.getUniqueId(), normalized);
        if (result != EconomyData.MutationResult.SUCCESS) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE,
                    plugin.tr("vault.error.balance_overflow"));
        }
        return new EconomyResponse(normalized, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ── String-based methods (legacy, delegate to OfflinePlayer) ──

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasAccount(String playerName) {
        return playerName != null && !playerName.isBlank();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    @SuppressWarnings("deprecation")
    public double getBalance(String playerName) {
        if (playerName == null || playerName.isBlank()) return 0D;
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return getBalance(player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean has(String playerName, double amount) {
        if (playerName == null || playerName.isBlank()) return false;
        return has(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        if (playerName == null || playerName.isBlank()) return failure(0D, "vault.error.invalid_player");
        return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse depositPlayer(String playerName, double amount) {
        if (playerName == null || playerName.isBlank()) return failure(0D, "vault.error.invalid_player");
        return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean createPlayerAccount(String playerName) {
        return playerName != null && !playerName.isBlank();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    // ── Bank methods (not supported) ──

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.tr("vault.error.banks_not_supported"));
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }

    /**
     * EasyTrading stores whole coins in a signed long.  Vault exposes doubles,
     * so accepting NaN/Infinity/fractions or silently narrowing a huge value
     * would let integrations report a successful mutation for a different
     * amount.  Reject anything that cannot be represented exactly enough for
     * our integer economy.  Values at 2^63 and above are outside long range;
     * the hexadecimal literal avoids the rounding of (double) Long.MAX_VALUE.
     */
    private static Long normalizeAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0 || amount >= 0x1.0p63) {
            return null;
        }
        long normalized = (long) amount;
        if (normalized < 0 || (double) normalized != amount) {
            return null;
        }
        return normalized;
    }

    private EconomyResponse failure(double balance, String key) {
        return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE,
                plugin.tr(key));
    }
}
