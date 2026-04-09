package com.easytrading.paper;

import com.easytrading.paper.command.MarketCommand;
import com.easytrading.paper.data.*;
import com.easytrading.paper.gui.ConfirmationGui;
import com.easytrading.paper.gui.MarketGui;
import com.easytrading.paper.listener.PlayerListener;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EasyTradingPlugin extends JavaPlugin {

    private EconomyData economy;
    private MarketData marketData;
    private MarketConfig marketConfig;
    private MarketBankConfig bankConfig;
    private MarketBankState bankState;
    private TransactionHistoryData transactionHistory;
    private MarketNotifyData marketNotify;

    private final Map<UUID, PendingSale> pendingSales = new ConcurrentHashMap<>();
    private final Map<UUID, PendingBankTrade> pendingBank = new ConcurrentHashMap<>();
    private final Map<UUID, MarketGui> openMarketGuis = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmationGui> openConfirmations = new ConcurrentHashMap<>();
    private final Set<UUID> hudHidden = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> playerBossBars = new ConcurrentHashMap<>();
    private VaultEconomyProvider vaultProvider;

    private static final long PENDING_TIMEOUT_MS = 5 * 60 * 1000L;
    private int saveTaskId = -1;
    private int hudTaskId = -1;
    private int cleanupTaskId = -1;

    @Override
    public void onEnable() {
        Path dataFolder = getDataFolder().toPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            getLogger().severe("Could not create data folder: " + e);
        }

        economy = new EconomyData(dataFolder);
        marketData = new MarketData(dataFolder);
        marketConfig = new MarketConfig(dataFolder);
        bankConfig = new MarketBankConfig(dataFolder);
        bankState = new MarketBankState(dataFolder);
        transactionHistory = new TransactionHistoryData(dataFolder);
        marketNotify = new MarketNotifyData(dataFolder);

        marketConfig.ensureLoaded();
        bankConfig.ensureLoaded();

        // Register commands
        MarketCommand cmd = new MarketCommand(this);
        PluginCommand marketCmd = getCommand("market");
        if (marketCmd != null) {
            marketCmd.setExecutor(cmd);
            marketCmd.setTabCompleter(cmd);
        }
        PluginCommand checkCmd = getCommand("checkbalance");
        if (checkCmd != null) {
            checkCmd.setExecutor(cmd);
            checkCmd.setTabCompleter(cmd);
        }

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Auto-save every 5 minutes (6000 ticks)
        saveTaskId = Bukkit.getScheduler().runTaskTimer(this, this::saveAll, 6000L, 6000L).getTaskId();

        // HUD balance display via action bar every 3 seconds (60 ticks)
        hudTaskId = Bukkit.getScheduler().runTaskTimer(this, this::tickHud, 60L, 60L).getTaskId();

        // Cleanup stale pending trades every minute (1200 ticks)
        cleanupTaskId = Bukkit.getScheduler().runTaskTimer(this, this::cleanupStalePending, 1200L, 1200L).getTaskId();

        // Register Vault Economy provider
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            vaultProvider = new VaultEconomyProvider(this);
            Bukkit.getServicesManager().register(Economy.class, vaultProvider, this, ServicePriority.Normal);
            getLogger().info("Vault economy hook registered!");
        } else {
            getLogger().warning("Vault not found! Economy integration with other plugins (e.g. Towny) will not work.");
        }

        getLogger().info("EasyTrading Paper plugin enabled!");
    }

    @Override
    public void onDisable() {
        if (saveTaskId != -1) Bukkit.getScheduler().cancelTask(saveTaskId);
        if (hudTaskId != -1) Bukkit.getScheduler().cancelTask(hudTaskId);
        if (cleanupTaskId != -1) Bukkit.getScheduler().cancelTask(cleanupTaskId);

        // Unregister Vault provider
        if (vaultProvider != null) {
            Bukkit.getServicesManager().unregisterAll(this);
        }

        // Remove all BossBars
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar bar = playerBossBars.remove(player.getUniqueId());
            if (bar != null) {
                player.hideBossBar(bar);
            }
        }
        saveAll();
        getLogger().info("EasyTrading Paper plugin disabled!");
    }

    private void saveAll() {
        economy.save();
        marketData.save();
        bankState.save();
        transactionHistory.save();
        marketNotify.save();
        bankConfig.save();
    }

    private void tickHud() {
        bankState.ensureToday();
        bankConfig.ensureLoaded();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!hudHidden.contains(uuid)) {
                long balance = economy.get(uuid);
                Component name = Component.text("Balance: " + balance).color(NamedTextColor.GOLD);
                BossBar bar = playerBossBars.get(uuid);
                if (bar == null) {
                    bar = BossBar.bossBar(name, 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                    playerBossBars.put(uuid, bar);
                    player.showBossBar(bar);
                } else {
                    bar.name(name);
                }
            } else {
                BossBar bar = playerBossBars.remove(uuid);
                if (bar != null) {
                    player.hideBossBar(bar);
                }
            }
        }
    }

    private void cleanupStalePending() {
        long now = System.currentTimeMillis();
        pendingSales.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingBank.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
    }

    // ── Player Events ──

    public void onPlayerLogin(Player player) {
        syncBalance(player);

        // Load HUD preference
        // (hudHidden is ephemeral in Paper version; persisted separately if needed)

        MarketNotifyData notify = getMarketNotify();
        long currentVersion = notify.getVersion();
        Long lastSeen = notify.getLastSeen(player.getUniqueId());
        if (lastSeen != null && currentVersion > lastSeen) {
            player.sendMessage(Component.text("New items appeared on the market.").color(NamedTextColor.YELLOW));
        }
        notify.markSeen(player.getUniqueId(), currentVersion);
    }

    public void onPlayerLogout(Player player) {
        UUID uuid = player.getUniqueId();
        pendingSales.remove(uuid);
        pendingBank.remove(uuid);
        openMarketGuis.remove(uuid);
        openConfirmations.remove(uuid);
        BossBar bar = playerBossBars.remove(uuid);
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    // ── Balance Sync ──

    public void syncBalance(Player player) {
        if (player == null || !player.isOnline()) return;
        // In Paper, we rely on the HUD tick for action bar display
        // Immediate sync just for informational purposes
    }

    // ── GUI Registry ──

    public void registerMarketGui(Player player, MarketGui gui) {
        openMarketGuis.put(player.getUniqueId(), gui);
    }

    public MarketGui getOpenMarketGui(Player player) {
        return openMarketGuis.get(player.getUniqueId());
    }

    public void removeMarketGui(Player player) {
        openMarketGuis.remove(player.getUniqueId());
    }

    public void registerConfirmation(Player player, ConfirmationGui gui) {
        openConfirmations.put(player.getUniqueId(), gui);
    }

    public ConfirmationGui getOpenConfirmation(Player player) {
        return openConfirmations.get(player.getUniqueId());
    }

    public void removeConfirmation(Player player) {
        openConfirmations.remove(player.getUniqueId());
    }

    // ── Pending Trades ──

    public PendingSale getPendingSale(Player player) {
        return pendingSales.get(player.getUniqueId());
    }

    public void setPendingSale(Player player, PendingSale sale) {
        pendingSales.put(player.getUniqueId(), sale);
    }

    public PendingBankTrade getPendingBank(Player player) {
        return pendingBank.get(player.getUniqueId());
    }

    public void setPendingBank(Player player, PendingBankTrade trade) {
        pendingBank.put(player.getUniqueId(), trade);
    }

    // ── HUD ──

    public void setHudHidden(Player player, boolean hidden) {
        if (hidden) {
            hudHidden.add(player.getUniqueId());
            BossBar bar = playerBossBars.remove(player.getUniqueId());
            if (bar != null) {
                player.hideBossBar(bar);
            }
        } else {
            hudHidden.remove(player.getUniqueId());
        }
    }

    // ── Data Access ──

    public EconomyData getEconomy() { return economy; }
    public MarketData getMarketData() { return marketData; }
    public MarketConfig getMarketConfig() { return marketConfig; }
    public MarketBankConfig getBankConfig() { return bankConfig; }
    public MarketBankState getBankState() { return bankState; }
    public TransactionHistoryData getTransactionHistory() { return transactionHistory; }
    public MarketNotifyData getMarketNotify() { return marketNotify; }

    // ── Fee Computation ──

    public long computeFee(UUID seller, long price) {
        marketConfig.ensureLoaded();
        int count = marketData.countBySeller(seller);
        if (count < marketConfig.getFreeListingSlots()) return 0L;
        int overload = Math.max(0, count - marketConfig.getSoftListingCap() + 1);
        int feePercent = marketConfig.getBaseFeePercent() + overload * marketConfig.getProgressiveFeePercent();
        long rawFee = Math.round(price * feePercent / 100.0);
        long withMin = Math.max(marketConfig.getFeeMin(), rawFee);
        return Math.min(marketConfig.getFeeMax(), withMin);
    }

    // ── Trade Confirmation Handlers ──

    public void handleSellConfirm(Player player, boolean confirmed) {
        PendingSale pending = pendingSales.remove(player.getUniqueId());
        if (pending == null) return;

        if (!confirmed) {
            player.sendMessage(Component.text("Listing cancelled.").color(NamedTextColor.YELLOW));
            return;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.isEmpty() || !inHand.isSimilar(pending.stack) || inHand.getAmount() < pending.stack.getAmount()) {
            player.sendMessage(Component.text("Item in hand changed. Operation cancelled.").color(NamedTextColor.RED));
            return;
        }

        if (economy.get(player.getUniqueId()) < pending.fee) {
            player.sendMessage(Component.text("Not enough funds for fee (" + pending.fee + ").").color(NamedTextColor.RED));
            return;
        }

        // Remove item from hand
        int removeCount = pending.stack.getAmount();
        if (inHand.getAmount() == removeCount) {
            player.getInventory().setItemInMainHand(ItemStack.empty());
        } else {
            inHand.setAmount(inHand.getAmount() - removeCount);
        }

        MarketData.Listing listing = marketData.add(player.getUniqueId(), pending.stack.clone(), pending.price, System.currentTimeMillis());

        MarketNotifyData notifyState = marketNotify;
        long version = notifyState.bump();
        for (Player online : Bukkit.getOnlinePlayers()) {
            notifyState.markSeen(online.getUniqueId(), version);
        }

        economy.add(player.getUniqueId(), -pending.fee);
        player.sendMessage(Component.text("Listing #" + listing.id + " created for " + pending.price + " (fee " + pending.fee + ").")
                .color(NamedTextColor.GREEN));
        MarketGui.refreshAll();

        if (pending.fee > 0) {
            transactionHistory.record(player.getUniqueId(), "FEE", MarketGui.getItemName(pending.stack),
                    pending.stack.getAmount(), -pending.fee, null);
        }
    }

    public void handleBankConfirm(Player player, boolean confirmed) {
        PendingBankTrade pending = pendingBank.remove(player.getUniqueId());
        if (pending == null) return;

        if (!confirmed) {
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, pending.action, pending.itemId,
                    pending.itemName, pending.requested,
                    pending.stack.isEmpty() ? 0 : pending.stack.getAmount(),
                    0, 0L, 0L, 0, 0,
                    economy.get(player.getUniqueId()), economy.get(player.getUniqueId()),
                    "CANCELLED", "user_cancel");
            player.sendMessage(Component.text("Operation cancelled.").color(NamedTextColor.YELLOW));
            return;
        }

        bankConfig.ensureLoaded();
        MarketBankConfig.BankResource resource = bankConfig.get(pending.itemId);
        if (resource == null) {
            player.sendMessage(Component.text("Resource unavailable.").color(NamedTextColor.RED));
            return;
        }

        bankState.ensureToday();

        if (pending.action.equals("sellto")) {
            handleBankSell(player, pending, resource);
        } else if (pending.action.equals("buyfrom")) {
            handleBankBuy(player, pending, resource);
        }
    }

    private void handleBankSell(Player player, PendingBankTrade pending, MarketBankConfig.BankResource resource) {
        ItemStack inHand = player.getInventory().getItemInMainHand();
        String inHandId = inHand.isEmpty() ? "" : MarketBankConfig.toMinecraftId(inHand.getType());
        if (inHand.isEmpty() || !inHandId.equals(pending.itemId)) {
            player.sendMessage(Component.text("Item in hand changed. Operation cancelled.").color(NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "sellto", pending.itemId, pending.itemName,
                    pending.requested, 0, 0, pending.price, 0L,
                    resource.limit(), 0,
                    economy.get(player.getUniqueId()), economy.get(player.getUniqueId()),
                    "REJECTED", "item_changed");
            return;
        }

        int remaining = bankState.getRemaining(player.getUniqueId(), pending.itemId, resource.limit());
        int available = inHand.getAmount();
        int requested = Math.min(pending.accepted, Math.min(remaining, available));
        int accepted = Math.max(0, requested);
        if (accepted <= 0) {
            player.sendMessage(Component.text("Daily sell limit for this resource is reached.").color(NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "sellto", pending.itemId, pending.itemName,
                    pending.requested, available, 0, pending.price, 0L,
                    resource.limit(), remaining,
                    economy.get(player.getUniqueId()), economy.get(player.getUniqueId()),
                    "REJECTED", "limit_reached");
            return;
        }

        long total = accepted * pending.price;
        long balanceBefore = economy.get(player.getUniqueId());

        // Remove items from hand
        if (inHand.getAmount() <= accepted) {
            player.getInventory().setItemInMainHand(ItemStack.empty());
        } else {
            inHand.setAmount(inHand.getAmount() - accepted);
        }

        economy.add(player.getUniqueId(), total);
        long balanceAfter = economy.get(player.getUniqueId());

        bankState.addSold(player.getUniqueId(), pending.itemId, accepted);
        bankConfig.applyStockChange(pending.itemId, accepted);

        transactionHistory.record(player.getUniqueId(), "BANK_SELL", pending.itemName, accepted, total, null);

        String status = accepted < pending.requested ? "PARTIAL" : "ACCEPTED";
        String reason = accepted < pending.requested ? "limit_partial" : "ok";
        if (accepted < pending.requested) {
            player.sendMessage(Component.text("Accepted " + accepted + " of " + pending.requested + ". The rest was rejected due to limits.")
                    .color(NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("Accepted " + accepted + " items for " + total + ".").color(NamedTextColor.GREEN));
        }
        MarketBankConfig.logTrade(getDataFolder().toPath(), player, "sellto", pending.itemId, pending.itemName,
                pending.requested, available, accepted, pending.price, total,
                resource.limit(), bankState.getRemaining(player.getUniqueId(), pending.itemId, resource.limit()),
                balanceBefore, balanceAfter, status, reason);
    }

    private void handleBankBuy(Player player, PendingBankTrade pending, MarketBankConfig.BankResource resource) {
        int buyLimit = bankConfig.getBuyLimit(resource);
        int remaining = bankState.getRemainingBuy(player.getUniqueId(), pending.itemId, buyLimit);
        if (remaining <= 0) {
            player.sendMessage(Component.text("Daily buy limit for this resource is reached.").color(NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "buyfrom", pending.itemId, pending.itemName,
                    pending.requested, 0, 0, pending.price, 0L,
                    buyLimit, remaining,
                    economy.get(player.getUniqueId()), economy.get(player.getUniqueId()),
                    "REJECTED", "limit_reached");
            return;
        }

        long balance = economy.get(player.getUniqueId());
        int affordable = (int) Math.min(Integer.MAX_VALUE, balance / pending.price);
        if (affordable <= 0) {
            player.sendMessage(Component.text("Not enough funds.").color(NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "buyfrom", pending.itemId, pending.itemName,
                    pending.requested, 0, 0, pending.price, 0L,
                    buyLimit, remaining, balance, balance,
                    "REJECTED", "no_money");
            return;
        }

        Material mat = MarketBankConfig.toMaterial(pending.itemId);
        if (mat == null) {
            player.sendMessage(Component.text("Resource unavailable.").color(NamedTextColor.RED));
            return;
        }

        int space = getMaxInsertable(player, new ItemStack(mat, 1));
        if (space <= 0) {
            player.sendMessage(Component.text("No inventory space.").color(NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "buyfrom", pending.itemId, pending.itemName,
                    pending.requested, 0, 0, pending.price, 0L,
                    buyLimit, remaining, balance, balance,
                    "REJECTED", "no_space");
            return;
        }

        int accepted = Math.min(pending.accepted, Math.min(remaining, Math.min(affordable, space)));
        if (accepted <= 0) {
            player.sendMessage(Component.text("Purchase cannot be completed.").color(NamedTextColor.RED));
            return;
        }

        long total = accepted * pending.price;
        long balanceBefore = economy.get(player.getUniqueId());
        economy.add(player.getUniqueId(), -total);
        long balanceAfter = economy.get(player.getUniqueId());

        // Give items
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(mat, accepted));
        if (!leftover.isEmpty()) {
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }

        bankState.addBought(player.getUniqueId(), pending.itemId, accepted);
        bankConfig.applyStockChange(pending.itemId, -accepted);

        transactionHistory.record(player.getUniqueId(), "BANK_BUY", pending.itemName, accepted, -total, null);

        String status = accepted < pending.requested ? "PARTIAL" : "ACCEPTED";
        String reason = accepted < pending.requested ? "limited" : "ok";
        if (accepted < pending.requested) {
            player.sendMessage(Component.text("Issued " + accepted + " of " + pending.requested + ". The rest is unavailable.")
                    .color(NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("Issued " + accepted + " items for " + total + ".").color(NamedTextColor.GREEN));
        }
        MarketBankConfig.logTrade(getDataFolder().toPath(), player, "buyfrom", pending.itemId, pending.itemName,
                pending.requested, 0, accepted, pending.price, total,
                buyLimit, bankState.getRemainingBuy(player.getUniqueId(), pending.itemId, buyLimit),
                balanceBefore, balanceAfter, status, reason);
    }

    public void handleBuyConfirm(Player player, boolean confirmed, int listingId) {
        if (!confirmed) return;

        Optional<MarketData.Listing> removedOpt = marketData.remove(listingId);
        if (removedOpt.isEmpty()) {
            player.sendMessage(Component.text("This listing has already been sold.").color(NamedTextColor.RED));
            return;
        }

        MarketData.Listing listing = removedOpt.get();
        if (listing.seller.equals(player.getUniqueId())) {
            // Return item to seller
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(listing.stack.clone());
            if (!leftover.isEmpty()) {
                for (ItemStack item : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }
            player.sendMessage(Component.text("Listing removed from sale.").color(NamedTextColor.GREEN));
            MarketGui.refreshAll();
            return;
        }

        long balance = economy.get(player.getUniqueId());
        if (balance < listing.price) {
            marketData.putBack(listing);
            player.sendMessage(Component.text("Not enough funds.").color(NamedTextColor.RED));
            return;
        }

        if (!canFitInInventory(player, listing.stack)) {
            marketData.putBack(listing);
            player.sendMessage(Component.text("No inventory space.").color(NamedTextColor.RED));
            return;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(listing.stack.clone());
        if (!leftover.isEmpty()) {
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }

        economy.add(player.getUniqueId(), -listing.price);
        economy.add(listing.seller, listing.price);
        marketData.addSale(listing.price);

        String itemName = MarketGui.getItemName(listing.stack);
        int count = listing.stack.getAmount();

        player.sendMessage(Component.text("Purchase completed: " + itemName + " x" + count + " for " + listing.price + ".")
                .color(NamedTextColor.GREEN));

        Player sellerOnline = Bukkit.getPlayer(listing.seller);
        if (sellerOnline != null) {
            sellerOnline.sendMessage(Component.text("Your listing was sold (" + itemName + " x" + count + ", +" + listing.price + " to balance).")
                    .color(NamedTextColor.GREEN));
        }

        String sellerName = sellerOnline != null ? sellerOnline.getName() : listing.seller.toString();
        transactionHistory.record(player.getUniqueId(), "BUY", itemName, count, -listing.price, sellerName);
        transactionHistory.record(listing.seller, "SELL_INCOME", itemName, count, listing.price, player.getName());

        MarketGui.refreshAll();
    }

    private boolean canFitInInventory(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        int remaining = stack.getAmount();
        int maxPerStack = stack.getMaxStackSize();
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot == null || slot.isEmpty()) {
                remaining -= maxPerStack;
            } else if (slot.isSimilar(stack)) {
                remaining -= (maxPerStack - slot.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private int getMaxInsertable(Player player, ItemStack stack) {
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

    // ── Pending Trade Records ──

    public record PendingSale(ItemStack stack, long price, long fee, long createdAt) {}

    public static class PendingBankTrade {
        public final String action;
        public final ItemStack stack;
        public final String itemId;
        public final String itemName;
        public final int requested;
        public final int accepted;
        public final long price;
        public final long total;
        public final long createdAt;

        public PendingBankTrade(String action, ItemStack stack, String itemId, String itemName,
                                int requested, int accepted, long price, long total) {
            this.action = action;
            this.stack = stack;
            this.itemId = itemId;
            this.itemName = itemName;
            this.requested = requested;
            this.accepted = accepted;
            this.price = price;
            this.total = total;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
