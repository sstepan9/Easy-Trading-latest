package com.easytrading.paper.command;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.data.*;
import com.easytrading.paper.gui.ConfirmationGui;
import com.easytrading.paper.gui.MarketGui;
import com.easytrading.paper.trade.TradeManager;
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
import org.jetbrains.annotations.NotNull;

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

    private static final String HELP_TEXT =
            "Available commands:\n" +
            "/market - open market\n" +
            "/market sell <price> - list held item\n" +
            "/market sellto <amount> - sell item to bank\n" +
            "/market buyfrom <resource> <amount> - buy item from bank\n" +
            "/market send <nick> <amount> - transfer money\n" +
            "/market trade <player> - send a trade request\n" +
            "/market trade accept - accept a trade request\n" +
            "/market trade decline - decline a trade request\n" +
            "/market team [name] - team balance\n" +
            "/market history - transaction history\n" +
            "/market limits - remaining bank limits\n" +
            "/market hide | /market show - hide/show balance HUD\n" +
            "/market bankreload - reload bank rates\n" +
            "/market clearlimits - reset bank limits\n" +
            "/market change <percent> - set bank tax\n" +
            "/market help - show this help";

    public MarketCommand(EasyTradingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("checkbalance")) {
            return handleCheckBalance(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            openMarketScreen(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "help" -> { showHelp(player); yield true; }
            case "sell" -> handleSell(player, args);
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
            default -> {
                player.sendMessage(Component.text("Unknown subcommand. Use /market help").color(NamedTextColor.RED));
                yield true;
            }
        };
    }

    private void openMarketScreen(Player player) {
        MarketGui gui = new MarketGui(plugin, player);
        plugin.registerMarketGui(player, gui);
        gui.open();
    }

    private void showHelp(Player player) {
        for (String line : HELP_TEXT.split("\n")) {
            player.sendMessage(Component.text(line).color(NamedTextColor.WHITE));
        }
    }

    private boolean handleSell(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /market sell <price>").color(NamedTextColor.RED));
            return true;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.isEmpty()) {
            player.sendMessage(Component.text("Hold an item in your hand.").color(NamedTextColor.RED));
            return true;
        }

        String itemId = MarketBankConfig.toMinecraftId(inHand.getType());
        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        if (bankConfig.get(itemId) != null) {
            player.sendMessage(Component.text("This resource is sold via /market sellto.").color(NamedTextColor.RED));
            return true;
        }

        if (plugin.getPendingSale(player) != null) {
            player.sendMessage(Component.text("You already have a pending confirmation.").color(NamedTextColor.RED));
            return true;
        }

        long price;
        try {
            price = Long.parseLong(args[1]);
            if (price <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid price.").color(NamedTextColor.RED));
            return true;
        }

        MarketConfig config = plugin.getMarketConfig();
        config.ensureLoaded();
        if (price < config.getMinPrice() || price > config.getMaxPrice()) {
            player.sendMessage(Component.text("Price must be in range " + config.getMinPrice() + " - " + config.getMaxPrice() + ".")
                    .color(NamedTextColor.RED));
            return true;
        }

        int activeListings = plugin.getMarketData().countBySeller(player.getUniqueId());
        if (activeListings >= config.getHardListingCap()) {
            player.sendMessage(Component.text("Active listing limit reached (" + config.getHardListingCap() + ").")
                    .color(NamedTextColor.RED));
            return true;
        }

        long fee = plugin.computeFee(player.getUniqueId(), price);
        plugin.setPendingSale(player, new EasyTradingPlugin.PendingSale(inHand.clone(), price, fee, System.currentTimeMillis()));

        // Open confirmation GUI
        new ConfirmationGui(plugin, player, ConfirmationGui.Type.SELL,
                0, MarketGui.getItemName(inHand), inHand.getAmount(), price, fee, null, 0).open();
        return true;
    }

    private boolean handleSellTo(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /market sellto <amount>").color(NamedTextColor.RED));
            return true;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.isEmpty()) {
            player.sendMessage(Component.text("Hold an item in your hand.").color(NamedTextColor.RED));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
            if (amount <= 0 || amount > Integer.MAX_VALUE) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid amount.").color(NamedTextColor.RED));
            return true;
        }

        if (plugin.getPendingBank(player) != null) {
            player.sendMessage(Component.text("You already have a pending confirmation.").color(NamedTextColor.RED));
            return true;
        }

        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        String itemId = MarketBankConfig.toMinecraftId(inHand.getType());
        MarketBankConfig.BankResource resource = bankConfig.get(itemId);
        if (resource == null) {
            player.sendMessage(Component.text("This item is not accepted by the bank.").color(NamedTextColor.RED));
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
            player.sendMessage(Component.text("Invalid amount.").color(NamedTextColor.RED));
            return true;
        }

        long sellPrice = bankConfig.getSellPrice(itemId);
        MarketBankState bankState = plugin.getBankState();
        bankState.ensureToday();
        int remaining = bankState.getRemaining(player.getUniqueId(), itemId, resource.limit());
        if (remaining <= 0) {
            player.sendMessage(Component.text("Daily sell limit for this resource is reached.").color(NamedTextColor.RED));
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
            player.sendMessage(Component.text("Usage: /market buyfrom <resource> <amount>").color(NamedTextColor.RED));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount <= 0 || amount > Integer.MAX_VALUE) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid amount.").color(NamedTextColor.RED));
            return true;
        }

        if (plugin.getPendingBank(player) != null) {
            player.sendMessage(Component.text("You already have a pending confirmation.").color(NamedTextColor.RED));
            return true;
        }

        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        String resolved = bankConfig.resolveResourceId(args[1]);
        if (resolved == null) {
            player.sendMessage(Component.text("Unknown resource.").color(NamedTextColor.RED));
            return true;
        }

        MarketBankConfig.BankResource resource = bankConfig.get(resolved);
        if (resource == null) {
            player.sendMessage(Component.text("This resource is not sold by the bank.").color(NamedTextColor.RED));
            return true;
        }

        int requested = (int) amount;
        if (requested <= 0) {
            player.sendMessage(Component.text("Invalid amount.").color(NamedTextColor.RED));
            return true;
        }

        MarketBankState bankState = plugin.getBankState();
        bankState.ensureToday();
        int buyLimit = bankConfig.getBuyLimit(resource);
        int remaining = bankState.getRemainingBuy(player.getUniqueId(), resolved, buyLimit);
        if (remaining <= 0) {
            player.sendMessage(Component.text("Daily buy limit for this resource is reached.").color(NamedTextColor.RED));
            return true;
        }

        long price = bankConfig.getBuyPrice(resolved);
        long balance = plugin.getEconomy().get(player.getUniqueId());
        if (balance < price) {
            player.sendMessage(Component.text("Not enough funds.").color(NamedTextColor.RED));
            return true;
        }

        Material mat = MarketBankConfig.toMaterial(resolved);
        if (mat == null) {
            player.sendMessage(Component.text("Resource unavailable.").color(NamedTextColor.RED));
            return true;
        }

        int space = getMaxInsertable(player, new ItemStack(mat, 1));
        if (space <= 0) {
            player.sendMessage(Component.text("No inventory space.").color(NamedTextColor.RED));
            return true;
        }

        int accepted = Math.min(requested, Math.min(remaining, space));
        long total = accepted * price;

        String itemName = MarketGui.getItemName(new ItemStack(mat));
        plugin.setPendingBank(player, new EasyTradingPlugin.PendingBankTrade(
                "buyfrom", ItemStack.empty(), resolved, itemName,
                requested, accepted, price, total));

        new ConfirmationGui(plugin, player, ConfirmationGui.Type.BANK_BUY,
                accepted, itemName, requested, price, total, null,
                bankConfig.getTaxPercent()).open();
        return true;
    }

    private boolean handleSend(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /market send <player> <amount>").color(NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(Component.text("Player not found.").color(NamedTextColor.RED));
            return true;
        }
        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage(Component.text("You cannot send money to yourself.").color(NamedTextColor.RED));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid amount.").color(NamedTextColor.RED));
            return true;
        }

        EconomyData economy = plugin.getEconomy();
        if (economy.get(player.getUniqueId()) < amount) {
            player.sendMessage(Component.text("Not enough funds.").color(NamedTextColor.RED));
            return true;
        }

        economy.add(player.getUniqueId(), -amount);
        economy.add(target.getUniqueId(), amount);
        plugin.syncBalance(player);
        plugin.syncBalance(target);

        player.sendMessage(Component.text("Sent " + amount + " to player " + target.getName()).color(NamedTextColor.GREEN));
        target.sendMessage(Component.text("Received " + amount + " from " + player.getName()).color(NamedTextColor.GREEN));

        logTransfer(player, target, amount);

        TransactionHistoryData history = plugin.getTransactionHistory();
        history.record(player.getUniqueId(), "SEND", "", 0, -amount, target.getName());
        history.record(target.getUniqueId(), "SEND", "", 0, amount, player.getName());
        return true;
    }

    private boolean handleTeam(Player player, String[] args) {
        if (args.length >= 2) {
            if (!player.hasPermission("easytrading.admin")) {
                player.sendMessage(Component.text("Only OP can specify a team name.").color(NamedTextColor.RED));
                return true;
            }
            Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(args[1]);
            if (team == null) {
                player.sendMessage(Component.text("Team not found.").color(NamedTextColor.RED));
                return true;
            }
            long sum = sumTeamBalance(team);
            player.sendMessage(Component.text("Team " + team.getName() + " balance: " + sum).color(NamedTextColor.GREEN));
            return true;
        }

        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getPlayerTeam(player);
        if (team == null) {
            player.sendMessage(Component.text("You are not in a team.").color(NamedTextColor.RED));
            return true;
        }
        long sum = sumTeamBalance(team);
        player.sendMessage(Component.text("Team " + team.getName() + " balance: " + sum).color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleHistory(Player player) {
        TransactionHistoryData history = plugin.getTransactionHistory();
        List<TransactionHistoryData.Transaction> recent = history.getRecent(player.getUniqueId(), 10);
        if (recent.isEmpty()) {
            player.sendMessage(Component.text("Transaction history is empty.").color(NamedTextColor.YELLOW));
            return true;
        }
        player.sendMessage(Component.text("--- Transaction History ---").color(NamedTextColor.GOLD));
        for (TransactionHistoryData.Transaction t : recent) {
            player.sendMessage(Component.text(t.format()).color(NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean handleLimits(Player player) {
        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        MarketBankState bankState = plugin.getBankState();
        bankState.ensureToday();

        player.sendMessage(Component.text("--- Bank Limits for Today ---").color(NamedTextColor.GOLD));
        for (Map.Entry<String, MarketBankConfig.BankResource> entry : bankConfig.getResources().entrySet()) {
            String itemId = entry.getKey();
            MarketBankConfig.BankResource res = entry.getValue();
            String displayName = MarketBankConfig.getDisplayName(itemId);
            int sellRemaining = bankState.getRemaining(player.getUniqueId(), itemId, res.limit());
            int buyLimit = bankConfig.getBuyLimit(res);
            int buyRemaining = bankState.getRemainingBuy(player.getUniqueId(), itemId, buyLimit);
            int sellUsed = res.limit() - sellRemaining;
            int buyUsed = buyLimit - buyRemaining;
            player.sendMessage(Component.text(String.format("%s: sell %d/%d | buy %d/%d",
                    displayName, sellUsed, res.limit(), buyUsed, buyLimit)).color(NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean handleBalance(Player player) {
        long balance = plugin.getEconomy().get(player.getUniqueId());
        player.sendMessage(Component.text("Your balance: " + balance).color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleHide(Player player, boolean hide) {
        plugin.setHudHidden(player, hide);
        if (hide) {
            player.sendMessage(Component.text("Balance HUD hidden.").color(NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("Balance HUD shown.").color(NamedTextColor.GREEN));
        }
        return true;
    }

    private boolean handleBankReload(Player player) {
        if (!player.hasPermission("easytrading.admin")) {
            player.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
            return true;
        }
        plugin.getBankConfig().reload();
        player.sendMessage(Component.text("Bank rates reloaded.").color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleClearLimits(Player player) {
        if (!player.hasPermission("easytrading.admin")) {
            player.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
            return true;
        }
        plugin.getBankState().resetToday();
        player.sendMessage(Component.text("Bank limits reset.").color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleChange(Player player, String[] args) {
        if (!player.hasPermission("easytrading.admin")) {
            player.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /market change <percent>").color(NamedTextColor.RED));
            return true;
        }
        int percent;
        try {
            percent = Integer.parseInt(args[1]);
            if (percent < 0 || percent > 100) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid percentage (0-100).").color(NamedTextColor.RED));
            return true;
        }
        plugin.getBankConfig().setTaxPercent(percent);
        player.sendMessage(Component.text("Bank tax set to: " + percent + "%").color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleAdd(Player player, String[] args) {
        if (!player.hasPermission("easytrading.admin")) {
            player.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /market add <player> <amount>").color(NamedTextColor.RED));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(Component.text("Player not found or offline.").color(NamedTextColor.RED));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid amount.").color(NamedTextColor.RED));
            return true;
        }
        plugin.getEconomy().add(target.getUniqueId(), amount);
        plugin.syncBalance(target);
        player.sendMessage(Component.text("Added " + amount + " to player " + target.getName()).color(NamedTextColor.GREEN));
        target.sendMessage(Component.text("Balance increased by " + amount).color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleTake(Player player, String[] args) {
        if (!player.hasPermission("easytrading.admin")) {
            player.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /market take <player> <amount>").color(NamedTextColor.RED));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(Component.text("Player not found or offline.").color(NamedTextColor.RED));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid amount.").color(NamedTextColor.RED));
            return true;
        }
        plugin.getEconomy().add(target.getUniqueId(), -amount);
        plugin.syncBalance(target);
        player.sendMessage(Component.text("Removed " + amount + " from player " + target.getName()).color(NamedTextColor.GREEN));
        target.sendMessage(Component.text("Balance decreased by " + amount).color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleTrade(Player player, String[] args) {
        TradeManager tradeManager = plugin.getTradeManager();

        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /market trade <player> | accept | decline").color(NamedTextColor.RED));
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
            player.sendMessage(Component.text("Player not found or offline.").color(NamedTextColor.RED));
            return true;
        }
        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage(Component.text("You cannot trade with yourself.").color(NamedTextColor.RED));
            return true;
        }

        tradeManager.sendRequest(player, target);
        return true;
    }

    private boolean handleCheckBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("easytrading.admin")) {
            sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /checkbalance <player>").color(NamedTextColor.RED));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found or offline.").color(NamedTextColor.RED));
            return true;
        }
        long balance = plugin.getEconomy().get(target.getUniqueId());
        sender.sendMessage(Component.text(target.getName() + " balance: " + balance).color(NamedTextColor.GREEN));
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
        if (stack == null || stack.isEmpty()) return 0;
        int remaining = 0;
        int maxPerStack = stack.getMaxStackSize();
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot == null || slot.isEmpty()) {
                remaining += maxPerStack;
            } else if (slot.isSimilar(stack)) {
                remaining += maxPerStack - slot.getAmount();
            }
        }
        return remaining;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("checkbalance")) {
            if (args.length == 1) {
                return null; // default player names
            }
            return List.of();
        }

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of(
                    "sell", "sellto", "buyfrom", "send", "trade", "team", "history",
                    "limits", "balance", "hide", "show", "help"
            ));
            if (sender.hasPermission("easytrading.admin")) {
                subs.addAll(List.of("bankreload", "clearlimits", "change", "add", "take"));
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
        }

        return List.of();
    }
}
