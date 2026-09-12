package com.easytrading.paper.command;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.data.*;
import com.easytrading.paper.gui.ConfirmationGui;
import com.easytrading.paper.gui.MarketGui;
import com.easytrading.paper.trade.TradeManager;
import com.easytrading.paper.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MarketCommand implements CommandExecutor, TabCompleter {
    private final EasyTradingPlugin plugin;
    private static final DateTimeFormatter TRANSFER_LOG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final String[] HELP_KEYS = {
            "command.help.1", "command.help.2", "command.help.3", "command.help.4", "command.help.5",
            "command.help.6", "command.help.7", "command.help.8", "command.help.9", "command.help.10",
            "command.help.11", "command.help.12", "command.help.13", "command.help.14", "command.help.15",
            "command.help.16", "command.help.17", "command.help.18", "command.help.19", "command.help.20",
            "command.help.21", "command.help.22", "command.help.23"
    };

    public MarketCommand(EasyTradingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("checkbalance")) {
            return handleCheckBalance(sender, args);
        }

        if (!(sender instanceof Player player)) {
            plugin.sendMessage(sender, plugin.trc("command.player_only", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            openMarketScreen(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "help" -> { showHelp(player); yield true; }
            case "my" -> handleMy(player);
            case "seller" -> handleSeller(player, args);
            case "search" -> handleSearch(player, args);
            case "sell" -> handleSell(player, args);
            case "pur", "purchase" -> handlePurchase(player);
            case "relist" -> handleRelist(player, args);
            case "cancelall" -> handleCancelAll(player);
            case "sellto" -> handleSellTo(player, args);
            case "buyfrom" -> handleBuyFrom(player, args);
            case "send" -> handleSend(player, args);
            case "team" -> handleTeam(player, args);
            case "history" -> handleHistory(player);
            case "limits" -> handleLimits(player);
            case "balance" -> handleBalance(player);
            case "trade" -> handleTrade(player, args);
            case "hide" -> handleHide(player, true);
            case "show" -> handleHide(player, false);
            case "bankreload" -> handleBankReload(player);
            case "clearlimits" -> handleClearLimits(player);
            case "change" -> handleChange(player, args);
            case "add" -> handleAdd(player, args);
            case "take" -> handleTake(player, args);
            case "ads" -> handleAds(player, args);
            default -> {
                plugin.sendMessage(player, plugin.trc("command.unknown_subcommand", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private void openMarketScreen(Player player) {
        plugin.openMarket(player);
    }

    private void openMarketScreen(Player player, MarketGui.ViewOptions viewOptions) {
        plugin.openMarket(player, viewOptions);
    }

    private void showHelp(Player player) {
        for (String key : HELP_KEYS) {
            plugin.sendMessage(player, Component.text(plugin.tr(key)).color(NamedTextColor.WHITE));
        }
    }

    private boolean handleMy(Player player) {
        openMarketScreen(player, new MarketGui.ViewOptions(player.getUniqueId(), player.getName(), null,
                MarketGui.SortMode.NEWEST, MarketGui.DisplayMode.SELLING));
        return true;
    }

    private boolean handleSeller(Player player, String[] args) {
        if (args.length < 2) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_seller", NamedTextColor.RED));
            return true;
        }

        EasyTradingPlugin.MarketSellerMatch sellerMatch = plugin.findMarketSeller(args[1]);
        if (sellerMatch == null) {
            plugin.sendMessage(player, plugin.trc("command.seller_not_found", NamedTextColor.RED));
            return true;
        }

        openMarketScreen(player, new MarketGui.ViewOptions(
                sellerMatch.uuid(), sellerMatch.name(), null, MarketGui.SortMode.NEWEST, MarketGui.DisplayMode.SELLING));
        return true;
    }

    private boolean handleSearch(Player player, String[] args) {
        if (args.length < 2) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_search", NamedTextColor.RED));
            return true;
        }

        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (query.isEmpty()) {
            plugin.sendMessage(player, plugin.trc("command.search_empty", NamedTextColor.RED));
            return true;
        }

        openMarketScreen(player, new MarketGui.ViewOptions(null, null, query,
                MarketGui.SortMode.NEWEST, MarketGui.DisplayMode.SELLING));
        return true;
    }

    private boolean handleSell(Player player, String[] args) {
        if (args.length < 2) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_sell", NamedTextColor.RED));
            return true;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (Items.isEmpty(inHand)) {
            plugin.sendMessage(player, plugin.trc("error.hold_item_in_hand", NamedTextColor.RED));
            return true;
        }

        String itemId = MarketBankConfig.toMinecraftId(inHand.getType());
        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        if (bankConfig.get(itemId) != null) {
            plugin.sendMessage(player, plugin.trc("command.sellto_resource_hint", NamedTextColor.RED));
            return true;
        }

        if (plugin.getPendingSale(player) != null) {
            plugin.sendMessage(player, plugin.trc("error.pending_confirmation", NamedTextColor.RED));
            return true;
        }

        long price;
        try {
            price = parseListingPrice(args[1]);
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_price", NamedTextColor.RED));
            return true;
        }

        if (!validateListingPrice(player, price)) return true;

        MarketConfig config = plugin.getMarketConfig();
        config.ensureLoaded();

        int activeListings = plugin.getMarketData().countOffersByOwner(player.getUniqueId());
        if (activeListings >= config.getHardListingCap()) {
            plugin.sendMessage(player, plugin.trc("error.active_listing_limit", NamedTextColor.RED, config.getHardListingCap()));
            return true;
        }

        long fee = plugin.computeFee(player.getUniqueId(), price);
        plugin.setPendingSale(player, new EasyTradingPlugin.PendingSale(inHand.clone(), price, fee, System.currentTimeMillis()));

        // Open confirmation GUI
        new ConfirmationGui(plugin, player, ConfirmationGui.Type.SELL,
                0, MarketGui.getItemName(inHand), inHand.getAmount(), price, fee, null, 0).open();
        return true;
    }

    private boolean handlePurchase(Player player) {
        if (plugin.getPendingPurchaseOrder(player) != null) {
            plugin.sendMessage(player, plugin.trc("error.pending_confirmation", NamedTextColor.RED));
            return true;
        }
        if (plugin.hasPendingPurchaseBlockSearchPrompt(player) || plugin.hasPendingPurchaseTotalPricePrompt(player)) {
            plugin.sendMessage(player, plugin.trc("purchase_setup.finish_current", NamedTextColor.RED));
            return true;
        }

        plugin.openPurchaseSetup(player);
        return true;
    }

    private boolean handleRelist(Player player, String[] args) {
        if (args.length < 2) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_relist", NamedTextColor.RED));
            return true;
        }

        int listingId;
        try {
            listingId = Integer.parseInt(args[1]);
            if (listingId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_listing_id", NamedTextColor.RED));
            return true;
        }

        Optional<MarketData.Listing> listingOpt = plugin.getMarketData().getListing(listingId);
        if (listingOpt.isEmpty()) {
            plugin.sendMessage(player, plugin.trc("listing.not_found", NamedTextColor.RED));
            return true;
        }

        MarketData.Listing listing = listingOpt.get();
        if (!listing.seller.equals(player.getUniqueId())) {
            plugin.sendMessage(player, plugin.trc("listing.relist_own_only", NamedTextColor.RED));
            return true;
        }

        long newPrice = listing.price;
        if (args.length >= 3) {
            try {
                newPrice = parseListingPrice(args[2]);
            } catch (NumberFormatException e) {
                plugin.sendMessage(player, plugin.trc("error.invalid_price", NamedTextColor.RED));
                return true;
            }
        }

        if (!validateListingPrice(player, newPrice)) return true;

        plugin.getMarketData().relist(listingId, newPrice, System.currentTimeMillis());
        plugin.refreshAllMarketGuis();
        plugin.sendMessage(player, plugin.trc("listing.relisted", NamedTextColor.GREEN, listingId, newPrice));
        return true;
    }

    private boolean handleCancelAll(Player player) {
        List<MarketData.Listing> removed = plugin.getMarketData().removeBySeller(player.getUniqueId());
        if (removed.isEmpty()) {
            plugin.sendMessage(player, plugin.trc("listing.none_active", NamedTextColor.YELLOW));
            return true;
        }

        int totalItems = 0;
        for (MarketData.Listing listing : removed) {
            totalItems += listing.stack.getAmount();
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(listing.stack.clone());
            if (!leftover.isEmpty()) {
                for (ItemStack item : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }
        }

        plugin.refreshAllMarketGuis();
        plugin.sendMessage(player, plugin.trc("listing.cancel_all_done", NamedTextColor.GREEN, removed.size(), totalItems));
        return true;
    }

    private boolean handleSellTo(Player player, String[] args) {
        if (args.length < 2) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_sellto", NamedTextColor.RED));
            return true;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (Items.isEmpty(inHand)) {
            plugin.sendMessage(player, plugin.trc("error.hold_item_in_hand", NamedTextColor.RED));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
            if (amount <= 0 || amount > Integer.MAX_VALUE) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_amount", NamedTextColor.RED));
            return true;
        }

        if (plugin.getPendingBank(player) != null) {
            plugin.sendMessage(player, plugin.trc("error.pending_confirmation", NamedTextColor.RED));
            return true;
        }

        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        String itemId = MarketBankConfig.toMinecraftId(inHand.getType());
        MarketBankConfig.BankResource resource = bankConfig.get(itemId);
        if (resource == null) {
            plugin.sendMessage(player, plugin.trc("bank.item_not_accepted", NamedTextColor.RED));
            MarketBankConfig.logTrade(plugin.getDataFolder().toPath(), player, "sellto", itemId,
                    MarketGui.getItemName(inHand), (int) amount, inHand.getAmount(),
                    0, 0L, 0L, 0, 0,
                    plugin.getEconomy().get(player.getUniqueId()),
                    plugin.getEconomy().get(player.getUniqueId()),
                    "REJECTED", "not_accepted");
            return true;
        }

        int inHandCount = inHand.getAmount();
        int requestedInput = (int) Math.min(amount, Integer.MAX_VALUE);
        int requested = Math.min(requestedInput, inHandCount);
        if (requested <= 0) {
            plugin.sendMessage(player, plugin.trc("error.invalid_amount", NamedTextColor.RED));
            return true;
        }

        long sellPrice = bankConfig.getSellPrice(itemId);
        MarketBankState bankState = plugin.getBankState();
        bankState.ensureToday();
        int remaining = bankState.getRemaining(player.getUniqueId(), itemId, resource.limit());
        if (remaining <= 0) {
            plugin.sendMessage(player, plugin.trc("bank.sell_limit_reached", NamedTextColor.RED));
            MarketBankConfig.logTrade(plugin.getDataFolder().toPath(), player, "sellto", itemId,
                    MarketGui.getItemName(inHand), requestedInput, inHandCount,
                    0, sellPrice, 0L, resource.limit(), remaining,
                    plugin.getEconomy().get(player.getUniqueId()),
                    plugin.getEconomy().get(player.getUniqueId()),
                    "REJECTED", "limit_reached");
            return true;
        }

        int accepted = Math.min(requested, remaining);
        long total = accepted * sellPrice;

        plugin.setPendingBank(player, new EasyTradingPlugin.PendingBankTrade(
                "sellto", inHand.clone(), itemId, MarketGui.getItemName(inHand),
                requestedInput, accepted, sellPrice, total));

        new ConfirmationGui(plugin, player, ConfirmationGui.Type.BANK_SELL,
                accepted, MarketGui.getItemName(inHand), requestedInput, sellPrice, total, null,
                bankConfig.getTaxPercent()).open();
        return true;
    }

    private boolean handleBuyFrom(Player player, String[] args) {
        if (args.length < 3) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_buyfrom", NamedTextColor.RED));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount <= 0 || amount > Integer.MAX_VALUE) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_amount", NamedTextColor.RED));
            return true;
        }

        if (plugin.getPendingBank(player) != null) {
            plugin.sendMessage(player, plugin.trc("error.pending_confirmation", NamedTextColor.RED));
            return true;
        }

        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        String resolved = bankConfig.resolveResourceId(args[1]);
        if (resolved == null) {
            plugin.sendMessage(player, plugin.trc("bank.unknown_resource", NamedTextColor.RED));
            return true;
        }

        MarketBankConfig.BankResource resource = bankConfig.get(resolved);
        if (resource == null) {
            plugin.sendMessage(player, plugin.trc("bank.resource_not_sold", NamedTextColor.RED));
            return true;
        }

        int requested = (int) amount;
        if (requested <= 0) {
            plugin.sendMessage(player, plugin.trc("error.invalid_amount", NamedTextColor.RED));
            return true;
        }

        MarketBankState bankState = plugin.getBankState();
        bankState.ensureToday();
        int buyLimit = bankConfig.getBuyLimit(resource);
        int remaining = bankState.getRemainingBuy(player.getUniqueId(), resolved, buyLimit);
        if (remaining <= 0) {
            plugin.sendMessage(player, plugin.trc("bank.buy_limit_reached", NamedTextColor.RED));
            return true;
        }

        long price = bankConfig.getBuyPrice(resolved);
        long balance = plugin.getEconomy().get(player.getUniqueId());
        if (balance < price) {
            plugin.sendMessage(player, plugin.trc("error.not_enough_funds", NamedTextColor.RED));
            return true;
        }

        Material mat = MarketBankConfig.toMaterial(resolved);
        if (mat == null) {
            plugin.sendMessage(player, plugin.trc("error.resource_unavailable", NamedTextColor.RED));
            return true;
        }

        int space = getMaxInsertable(player, new ItemStack(mat, 1));
        if (space <= 0) {
            plugin.sendMessage(player, plugin.trc("error.no_inventory_space", NamedTextColor.RED));
            return true;
        }

        int accepted = Math.min(requested, Math.min(remaining, space));
        long total = accepted * price;

        String itemName = MarketGui.getItemName(new ItemStack(mat));
        plugin.setPendingBank(player, new EasyTradingPlugin.PendingBankTrade(
                "buyfrom", Items.empty(), resolved, itemName,
                requested, accepted, price, total));

        new ConfirmationGui(plugin, player, ConfirmationGui.Type.BANK_BUY,
                accepted, itemName, requested, price, total, null,
                bankConfig.getTaxPercent()).open();
        return true;
    }

    private boolean handleSend(Player player, String[] args) {
        if (args.length < 3) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_send", NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.sendMessage(player, plugin.trc("error.player_not_found", NamedTextColor.RED));
            return true;
        }
        if (player.getUniqueId().equals(target.getUniqueId())) {
            plugin.sendMessage(player, plugin.trc("command.send.self", NamedTextColor.RED));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_amount", NamedTextColor.RED));
            return true;
        }

        EconomyData economy = plugin.getEconomy();
        if (economy.get(player.getUniqueId()) < amount) {
            plugin.sendMessage(player, plugin.trc("error.not_enough_funds", NamedTextColor.RED));
            return true;
        }

        economy.add(player.getUniqueId(), -amount);
        economy.add(target.getUniqueId(), amount);
        plugin.syncBalance(player);

        plugin.sendMessage(player, plugin.trc("command.send.done_sender", NamedTextColor.GREEN, amount, target.getName()));
        plugin.getSchedulerAdapter().runPlayer(target, () -> {
            plugin.syncBalance(target);
            plugin.sendMessage(target, plugin.trc("command.send.done_target", NamedTextColor.GREEN,
                    amount, player.getName()));
        });

        logTransfer(player, target, amount);

        TransactionHistoryData history = plugin.getTransactionHistory();
        history.record(player.getUniqueId(), "SEND", "", 0, -amount, target.getName());
        history.record(target.getUniqueId(), "SEND", "", 0, amount, player.getName());
        return true;
    }

    private boolean handleTeam(Player player, String[] args) {
        if (args.length >= 2) {
            if (!player.hasPermission("easytrading.admin")) {
                plugin.sendMessage(player, plugin.trc("command.team.admin_only", NamedTextColor.RED));
                return true;
            }
            Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(args[1]);
            if (team == null) {
                plugin.sendMessage(player, plugin.trc("command.team.not_found", NamedTextColor.RED));
                return true;
            }
            long sum = sumTeamBalance(team);
            plugin.sendMessage(player, plugin.trc("command.team.balance", NamedTextColor.GREEN, team.getName(), sum));
            return true;
        }

        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getPlayerTeam(player);
        if (team == null) {
            plugin.sendMessage(player, plugin.trc("command.team.no_team", NamedTextColor.RED));
            return true;
        }
        long sum = sumTeamBalance(team);
        plugin.sendMessage(player, plugin.trc("command.team.balance", NamedTextColor.GREEN, team.getName(), sum));
        return true;
    }

    private boolean handleHistory(Player player) {
        TransactionHistoryData history = plugin.getTransactionHistory();
        List<TransactionHistoryData.Transaction> recent = history.getRecent(player.getUniqueId(), 10);
        if (recent.isEmpty()) {
            plugin.sendMessage(player, plugin.trc("history.empty", NamedTextColor.YELLOW));
            return true;
        }
        plugin.sendMessage(player, plugin.trc("history.header", NamedTextColor.GOLD));
        for (TransactionHistoryData.Transaction t : recent) {
            plugin.sendMessage(player, Component.text(t.format(plugin.getLocalization())).color(NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean handleLimits(Player player) {
        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        MarketBankState bankState = plugin.getBankState();
        bankState.ensureToday();

        plugin.sendMessage(player, plugin.trc("bank.limits.header", NamedTextColor.GOLD));
        for (Map.Entry<String, MarketBankConfig.BankResource> entry : bankConfig.getResources().entrySet()) {
            String itemId = entry.getKey();
            MarketBankConfig.BankResource res = entry.getValue();
            String displayName = MarketBankConfig.getDisplayName(itemId);
            int sellRemaining = bankState.getRemaining(player.getUniqueId(), itemId, res.limit());
            int buyLimit = bankConfig.getBuyLimit(res);
            int buyRemaining = bankState.getRemainingBuy(player.getUniqueId(), itemId, buyLimit);
            int sellUsed = res.limit() - sellRemaining;
            int buyUsed = buyLimit - buyRemaining;
            plugin.sendMessage(player, Component.text(plugin.tr("bank.limits.line",
                    displayName, sellUsed, res.limit(), buyUsed, buyLimit)).color(NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean handleBalance(Player player) {
        long balance = plugin.getEconomy().get(player.getUniqueId());
        plugin.sendMessage(player, plugin.trc("command.balance.self", NamedTextColor.GREEN, balance));
        return true;
    }

    private boolean handleHide(Player player, boolean hide) {
        plugin.setHudHidden(player, hide);
        if (hide) {
            plugin.sendMessage(player, plugin.trc("hud.hidden", NamedTextColor.YELLOW));
        } else {
            plugin.sendMessage(player, plugin.trc("hud.shown", NamedTextColor.GREEN));
        }
        return true;
    }

    private boolean handleBankReload(Player player) {
        if (!player.hasPermission("easytrading.admin")) {
            plugin.sendMessage(player, plugin.trc("error.no_permission", NamedTextColor.RED));
            return true;
        }
        plugin.getBankConfig().reload();
        plugin.sendMessage(player, plugin.trc("bank.reload.done", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleClearLimits(Player player) {
        if (!player.hasPermission("easytrading.admin")) {
            plugin.sendMessage(player, plugin.trc("error.no_permission", NamedTextColor.RED));
            return true;
        }
        plugin.getBankState().resetToday();
        plugin.sendMessage(player, plugin.trc("bank.clear_limits.done", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleChange(Player player, String[] args) {
        if (!player.hasPermission("easytrading.admin")) {
            plugin.sendMessage(player, plugin.trc("error.no_permission", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_change", NamedTextColor.RED));
            return true;
        }
        int percent;
        try {
            percent = Integer.parseInt(args[1]);
            if (percent < 0 || percent > 100) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_percentage", NamedTextColor.RED));
            return true;
        }
        plugin.getBankConfig().setTaxPercent(percent);
        plugin.sendMessage(player, plugin.trc("bank.tax_set", NamedTextColor.GREEN, percent));
        return true;
    }

    private boolean handleAds(Player player, String[] args) {
        if (!player.hasPermission("easytrading.admin")) {
            plugin.sendMessage(player, plugin.trc("error.no_permission", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_ads", NamedTextColor.RED));
            return true;
        }

        int limit;
        try {
            limit = Integer.parseInt(args[1]);
            if (limit < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_promoted_limit", NamedTextColor.RED));
            return true;
        }

        MarketConfig config = plugin.getMarketConfig();
        config.ensureLoaded();
        config.setPromotedListingLimit(limit);
        plugin.sendMessage(player, plugin.trc("admin.ads_limit_set", NamedTextColor.GREEN, limit));
        return true;
    }

    private boolean handleAdd(Player player, String[] args) {
        if (!player.hasPermission("easytrading.admin")) {
            plugin.sendMessage(player, plugin.trc("error.no_permission", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_add", NamedTextColor.RED));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.sendMessage(player, plugin.trc("error.player_not_found_offline", NamedTextColor.RED));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_amount", NamedTextColor.RED));
            return true;
        }
        plugin.getEconomy().add(target.getUniqueId(), amount);
        plugin.sendMessage(player, plugin.trc("admin.add.done_sender", NamedTextColor.GREEN, amount, target.getName()));
        plugin.getSchedulerAdapter().runPlayer(target, () -> {
            plugin.syncBalance(target);
            plugin.sendMessage(target, plugin.trc("admin.add.done_target", NamedTextColor.GREEN, amount));
        });
        return true;
    }

    private boolean handleTake(Player player, String[] args) {
        if (!player.hasPermission("easytrading.admin")) {
            plugin.sendMessage(player, plugin.trc("error.no_permission", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_take", NamedTextColor.RED));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.sendMessage(player, plugin.trc("error.player_not_found_offline", NamedTextColor.RED));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.sendMessage(player, plugin.trc("error.invalid_amount", NamedTextColor.RED));
            return true;
        }
        plugin.getEconomy().add(target.getUniqueId(), -amount);
        plugin.sendMessage(player, plugin.trc("admin.take.done_sender", NamedTextColor.GREEN, amount, target.getName()));
        plugin.getSchedulerAdapter().runPlayer(target, () -> {
            plugin.syncBalance(target);
            plugin.sendMessage(target, plugin.trc("admin.take.done_target", NamedTextColor.GREEN, amount));
        });
        return true;
    }

    private boolean handleTrade(Player player, String[] args) {
        if (!plugin.isTradeSupported()) {
            plugin.sendMessage(player, plugin.trc("trade.error.folia_disabled", NamedTextColor.RED));
            return true;
        }

        TradeManager tradeManager = plugin.getTradeManager();

        if (args.length < 2) {
            plugin.sendMessage(player, plugin.trc("command.usage.market_trade", NamedTextColor.RED));
            return true;
        }

        String sub = args[1].toLowerCase();

        if (sub.equals("accept")) {
            tradeManager.acceptRequest(player);
            return true;
        }

        if (sub.equals("decline")) {
            tradeManager.declineRequest(player);
            return true;
        }

        // /market trade <player> — send a request
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.sendMessage(player, plugin.trc("error.player_not_found_offline", NamedTextColor.RED));
            return true;
        }
        if (player.getUniqueId().equals(target.getUniqueId())) {
            plugin.sendMessage(player, plugin.trc("trade.error.self_trade", NamedTextColor.RED));
            return true;
        }

        tradeManager.sendRequest(player, target);
        return true;
    }

    private boolean handleCheckBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("easytrading.admin")) {
            plugin.sendMessage(sender, plugin.trc("error.no_permission", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            plugin.sendMessage(sender, plugin.trc("command.usage.checkbalance", NamedTextColor.RED));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.sendMessage(sender, plugin.trc("error.player_not_found_offline", NamedTextColor.RED));
            return true;
        }
        long balance = plugin.getEconomy().get(target.getUniqueId());
        plugin.sendMessage(sender, plugin.trc("command.balance.other", NamedTextColor.GREEN, target.getName(), balance));
        return true;
    }

    private long sumTeamBalance(Team team) {
        EconomyData economy = plugin.getEconomy();
        long sum = 0L;
        for (String entry : team.getEntries()) {
            Player online = Bukkit.getPlayerExact(entry);
            if (online != null) {
                sum += economy.get(online.getUniqueId());
            }
        }
        return sum;
    }


    private boolean validateListingPrice(Player player, long price) {
        MarketConfig config = plugin.getMarketConfig();
        config.ensureLoaded();
        if (price < config.getMinPrice() || price > config.getMaxPrice()) {
            plugin.sendMessage(player, plugin.trc("error.price_range", NamedTextColor.RED, config.getMinPrice(), config.getMaxPrice()));
            return false;
        }
        return true;
    }

    private long parseListingPrice(String rawPrice) {
        long price = Long.parseLong(rawPrice);
        if (price <= 0) {
            throw new NumberFormatException();
        }
        return price;
    }

    private void logTransfer(Player sender, Player target, long amount) {
        try {
            Path logDir = plugin.getDataFolder().toPath().resolve("logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("easytrading-transfers.log");
            String time = TRANSFER_LOG_FMT.format(Instant.now());
            String line = String.join(" | ",
                    "ts=" + time,
                    "from=" + sender.getName(),
                    "from_uuid=" + sender.getUniqueId(),
                    "to=" + target.getName(),
                    "to_uuid=" + target.getUniqueId(),
                    "amount=" + amount
            );
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write transfer log: " + e);
        }
    }

    static int getMaxInsertable(Player player, ItemStack stack) {
        if (stack == null || Items.isEmpty(stack)) return 0;
        int remaining = 0;
        int maxPerStack = stack.getMaxStackSize();
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot == null || Items.isEmpty(slot)) {
                remaining += maxPerStack;
            } else if (slot.isSimilar(stack)) {
                remaining += maxPerStack - slot.getAmount();
            }
        }
        return remaining;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("checkbalance")) {
            if (args.length == 1) {
                return null; // default player names
            }
            return List.of();
        }

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of(
                    "my", "seller", "search", "sell", "relist", "cancelall", "sellto", "buyfrom",
                    "pur", "purchase", "send", "trade", "team", "history",
                    "limits", "balance", "hide", "show", "help"
            ));
            if (sender.hasPermission("easytrading.admin")) {
                subs.addAll(List.of("bankreload", "clearlimits", "change", "add", "take", "ads"));
            }
            String prefix = args[0].toLowerCase();
            return subs.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("buyfrom")) {
                String prefix = args[1].toLowerCase();
                return Arrays.stream(MarketBankConfig.BUY_SUGGESTIONS)
                        .filter(s -> s.startsWith(prefix)).toList();
            }
            if (sub.equals("seller")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return plugin.getMarketData().getAllListingsSorted().stream()
                        .map(listing -> plugin.resolveMarketSellerName(listing.seller))
                        .filter(Objects::nonNull)
                        .distinct()
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (sub.equals("send") || sub.equals("add") || sub.equals("take") || sub.equals("trade")) {
                if (sub.equals("trade")) {
                    String prefix = args[1].toLowerCase();
                    List<String> options = new ArrayList<>(List.of("accept", "decline"));
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (sender instanceof Player sp && p.getUniqueId().equals(sp.getUniqueId())) continue;
                        options.add(p.getName());
                    }
                    return options.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
                }
                return null; // default player names
            }
            if (sub.equals("relist") && sender instanceof Player player) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return plugin.getMarketData().getListingsBySeller(player.getUniqueId()).stream()
                        .map(listing -> String.valueOf(listing.id))
                        .filter(id -> id.startsWith(prefix))
                        .toList();
            }
        }

        return List.of();
    }
}



