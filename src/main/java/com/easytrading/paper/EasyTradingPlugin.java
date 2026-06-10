package com.easytrading.paper;

import com.easytrading.paper.command.MarketCommand;
import com.easytrading.paper.data.*;
import com.easytrading.paper.gui.ConfirmationGui;
import com.easytrading.paper.gui.MarketGui;
import com.easytrading.paper.gui.PurchaseSetupGui;
import com.easytrading.paper.listener.PlayerListener;
import com.easytrading.paper.locale.LocalizationManager;
import com.easytrading.paper.platform.PlatformScheduler;
import com.easytrading.paper.trade.TradeManager;
import com.easytrading.paper.util.AdventureSupport;
import com.easytrading.paper.util.Items;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
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

    private static EasyTradingPlugin instance;

    private EconomyData economy;
    private MarketData marketData;
    private MarketConfig marketConfig;
    private MarketBankConfig bankConfig;
    private MarketBankState bankState;
    private TransactionHistoryData transactionHistory;
    private MarketNotifyData marketNotify;
    private MarketDeliveryData marketDeliveries;

    private final Map<UUID, PendingSale> pendingSales = new ConcurrentHashMap<>();
    private final Map<UUID, PendingBankTrade> pendingBank = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPurchaseOrder> pendingPurchaseOrders = new ConcurrentHashMap<>();
    private final Map<UUID, PurchaseBlockSearchPrompt> pendingPurchaseBlockSearchPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, PurchaseTotalPricePrompt> pendingPurchaseTotalPricePrompts = new ConcurrentHashMap<>();
    private final Map<UUID, MarketFilterPrompt> pendingMarketPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, MarketGui> openMarketGuis = new ConcurrentHashMap<>();
    private final Map<UUID, PurchaseSetupGui> openPurchaseSetupGuis = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmationGui> openConfirmations = new ConcurrentHashMap<>();
    private final Set<UUID> hudHidden = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> playerBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, PlatformScheduler.TaskHandle> playerHudTasks = new ConcurrentHashMap<>();
    private VaultEconomyProvider vaultProvider;
    private TradeManager tradeManager;
    private AdventureSupport adventure;
    private PlatformScheduler scheduler;
    private LocalizationManager localization;

    private static final long PENDING_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final Set<String> CANCEL_KEYWORDS = Set.of("cancel", "отмена", "отменить");
    private static final Set<String> CLEAR_KEYWORDS = Set.of("clear", "очистить", "сброс", "reset");
    private PlatformScheduler.TaskHandle saveTask;
    private PlatformScheduler.TaskHandle cleanupTask;
    private PlatformScheduler.TaskHandle tradeCleanupTask;

    @Override
    public void onEnable() {
        instance = this;
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
        marketDeliveries = new MarketDeliveryData(dataFolder);

        marketConfig.ensureLoaded();
        bankConfig.ensureLoaded();

        localization = new LocalizationManager();
        localization.loadInternalBundle(this, "en_us", "lang/en_us.properties");

        adventure = new AdventureSupport(this);
        adventure.enable();
        scheduler = new PlatformScheduler(this);
        tradeManager = new TradeManager(this);

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
        saveTask = scheduler.runGlobalRepeating(this::saveAll, 6000L, 6000L);

        // Cleanup stale pending trades every minute (1200 ticks)
        cleanupTask = scheduler.runGlobalRepeating(this::cleanupStalePending, 1200L, 1200L);

        // Cleanup expired trade requests every 20 seconds (400 ticks)
        tradeCleanupTask = scheduler.runGlobalRepeating(() -> tradeManager.cleanupExpired(), 400L, 400L);

        // Register Vault Economy provider
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            vaultProvider = new VaultEconomyProvider(this);
            Bukkit.getServicesManager().register(Economy.class, vaultProvider, this, ServicePriority.Normal);
            getLogger().info("Vault economy hook registered!");
        } else {
            getLogger().warning("Vault not found! Economy integration with other plugins (e.g. Towny) will not work.");
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            startPlayerHudTask(player);
        }

        getLogger().info("EasyTrading Paper plugin enabled!");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) saveTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        if (tradeCleanupTask != null) tradeCleanupTask.cancel();
        for (PlatformScheduler.TaskHandle handle : playerHudTasks.values()) {
            handle.cancel();
        }
        playerHudTasks.clear();

        // Unregister Vault provider
        if (vaultProvider != null) {
            Bukkit.getServicesManager().unregisterAll(this);
        }

        // Remove all BossBars
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar bar = playerBossBars.remove(player.getUniqueId());
            if (bar != null) {
                hideBossBar(player, bar);
            }
        }
        if (adventure != null) {
            adventure.disable();
        }
        saveAll();
        getLogger().info("EasyTrading Paper plugin disabled!");
        instance = null;
    }

    private void saveAll() {
        economy.save();
        marketData.save();
        bankState.save();
        transactionHistory.save();
        marketNotify.save();
        marketDeliveries.save();
        bankConfig.save();
    }

    private void tickPlayerHud(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        bankState.ensureToday();
        bankConfig.ensureLoaded();

        UUID uuid = player.getUniqueId();
        if (!hudHidden.contains(uuid)) {
            long balance = economy.get(uuid);
            Component name = trc("hud.balance", NamedTextColor.GOLD, balance);
            BossBar bar = playerBossBars.get(uuid);
            if (bar == null) {
                bar = BossBar.bossBar(name, 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                playerBossBars.put(uuid, bar);
                showBossBar(player, bar);
            } else {
                bar.name(name);
            }
        } else {
            BossBar bar = playerBossBars.remove(uuid);
            if (bar != null) {
                hideBossBar(player, bar);
            }
        }

        if (marketDeliveries.has(uuid)) {
            deliverQueuedItems(player);
        }
    }

    private void startPlayerHudTask(Player player) {
        stopPlayerHudTask(player);
        playerHudTasks.put(player.getUniqueId(), scheduler.runPlayerRepeating(player, () -> tickPlayerHud(player), 60L, 60L));
    }

    private void stopPlayerHudTask(Player player) {
        PlatformScheduler.TaskHandle handle = playerHudTasks.remove(player.getUniqueId());
        if (handle != null) {
            handle.cancel();
        }
    }

    private void cleanupStalePending() {
        long now = System.currentTimeMillis();
        pendingSales.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingBank.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingPurchaseOrders.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingPurchaseBlockSearchPrompts.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingPurchaseTotalPricePrompts.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
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
            sendMessage(player, trc("notify.market.new_offers", NamedTextColor.YELLOW));
        }
        notify.markSeen(player.getUniqueId(), currentVersion);
        startPlayerHudTask(player);
        deliverQueuedItems(player);
    }

    public void onPlayerLogout(Player player) {
        UUID uuid = player.getUniqueId();
        pendingSales.remove(uuid);
        pendingBank.remove(uuid);
        pendingPurchaseOrders.remove(uuid);
        pendingPurchaseBlockSearchPrompts.remove(uuid);
        pendingPurchaseTotalPricePrompts.remove(uuid);
        pendingMarketPrompts.remove(uuid);
        openMarketGuis.remove(uuid);
        openPurchaseSetupGuis.remove(uuid);
        openConfirmations.remove(uuid);
        tradeManager.handleDisconnect(player);
        stopPlayerHudTask(player);
        BossBar bar = playerBossBars.remove(uuid);
        if (bar != null) {
            hideBossBar(player, bar);
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

    public void openMarket(Player player) {
        openMarket(player, MarketGui.ViewOptions.defaults());
    }

    public void openMarket(Player player, MarketGui.ViewOptions viewOptions) {
        MarketGui gui = new MarketGui(this, player, viewOptions);
        registerMarketGui(player, gui);
        gui.open();
    }

    public MarketGui getOpenMarketGui(Player player) {
        return openMarketGuis.get(player.getUniqueId());
    }

    public void removeMarketGui(Player player) {
        openMarketGuis.remove(player.getUniqueId());
    }

    public void registerPurchaseSetupGui(Player player, PurchaseSetupGui gui) {
        openPurchaseSetupGuis.put(player.getUniqueId(), gui);
    }

    public void openPurchaseSetup(Player player) {
        openPurchaseSetup(player, PurchaseSetupGui.Stage.BLOCK, Material.STONE, 64, null);
    }

    public void openPurchaseSetup(Player player, PurchaseSetupGui.Stage stage, Material selectedMaterial, int amount, String searchQuery) {
        PurchaseSetupGui gui = new PurchaseSetupGui(this, player, stage, selectedMaterial, amount, searchQuery);
        registerPurchaseSetupGui(player, gui);
        gui.open();
    }

    public PurchaseSetupGui getOpenPurchaseSetupGui(Player player) {
        return openPurchaseSetupGuis.get(player.getUniqueId());
    }

    public void removePurchaseSetupGui(Player player) {
        openPurchaseSetupGuis.remove(player.getUniqueId());
    }

    public void promptMarketFilter(Player player, MarketFilterPromptType type, MarketGui.ViewOptions viewOptions) {
        MarketGui.ViewOptions safeViewOptions = viewOptions == null ? MarketGui.ViewOptions.defaults() : viewOptions;
        pendingMarketPrompts.put(player.getUniqueId(), new MarketFilterPrompt(type, safeViewOptions));
        player.closeInventory();

        if (type == MarketFilterPromptType.ITEM_QUERY) {
            sendMessage(player, trc("prompt.market.item_filter", NamedTextColor.YELLOW));
            return;
        }

        String label = safeViewOptions.displayMode() == MarketGui.DisplayMode.PURCHASING
                ? tr("common.buyer")
                : tr("common.seller");
        sendMessage(player, trc("prompt.market.owner_filter", NamedTextColor.YELLOW, label));
    }

    public boolean hasPendingMarketPrompt(Player player) {
        return pendingMarketPrompts.containsKey(player.getUniqueId());
    }

    public boolean hasPendingPurchaseTotalPricePrompt(Player player) {
        return pendingPurchaseTotalPricePrompts.containsKey(player.getUniqueId());
    }

    public boolean hasPendingPurchaseBlockSearchPrompt(Player player) {
        return pendingPurchaseBlockSearchPrompts.containsKey(player.getUniqueId());
    }

    public void handlePendingMarketPrompt(Player player, String input) {
        MarketFilterPrompt prompt = pendingMarketPrompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }

        String normalized = input == null ? "" : input.trim();
        if (isCancelKeyword(normalized)) {
            sendMessage(player, trc("prompt.market.cancelled", NamedTextColor.YELLOW));
            openMarket(player, prompt.viewOptions());
            return;
        }

        if (prompt.type() == MarketFilterPromptType.ITEM_QUERY) {
            MarketGui.ViewOptions nextOptions = isClearKeyword(normalized)
                    ? prompt.viewOptions().withItemQuery(null)
                    : prompt.viewOptions().withItemQuery(normalized);
            openMarket(player, nextOptions);
            return;
        }

        if (isClearKeyword(normalized)) {
            openMarket(player, prompt.viewOptions().withSellerFilter(null, null));
            return;
        }

        MarketSellerMatch sellerMatch = findMarketOwner(normalized, prompt.viewOptions().displayMode());
        if (sellerMatch == null) {
            String label = prompt.viewOptions().displayMode() == MarketGui.DisplayMode.PURCHASING
                    ? tr("common.buyer.capitalized")
                    : tr("common.seller.capitalized");
            sendMessage(player, trc("prompt.market.owner_not_found", NamedTextColor.RED, label));
            openMarket(player, prompt.viewOptions());
            return;
        }

        openMarket(player, prompt.viewOptions().withSellerFilter(sellerMatch.uuid(), sellerMatch.name()));
    }

    public void promptPurchaseBlockSearch(Player player, Material selectedMaterial, int amount, String currentSearchQuery) {
        pendingPurchaseBlockSearchPrompts.put(player.getUniqueId(),
                new PurchaseBlockSearchPrompt(selectedMaterial, amount, currentSearchQuery, System.currentTimeMillis()));
        player.closeInventory();
        sendMessage(player, trc("prompt.purchase.block_search", NamedTextColor.YELLOW));
    }

    public void handlePendingPurchaseBlockSearchPrompt(Player player, String input) {
        PurchaseBlockSearchPrompt prompt = pendingPurchaseBlockSearchPrompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }

        String normalized = input == null ? "" : input.trim();
        if (isCancelKeyword(normalized)) {
            openPurchaseSetup(player, PurchaseSetupGui.Stage.BLOCK, prompt.selectedMaterial(), prompt.amount(), prompt.searchQuery());
            return;
        }

        String nextQuery = isClearKeyword(normalized) ? null : normalized;
        openPurchaseSetup(player, PurchaseSetupGui.Stage.BLOCK, prompt.selectedMaterial(), prompt.amount(), nextQuery);
    }

    public void promptPurchaseTotalPrice(Player player, Material material, int amount, String searchQuery) {
        pendingPurchaseTotalPricePrompts.put(player.getUniqueId(),
                new PurchaseTotalPricePrompt(material, amount, searchQuery, System.currentTimeMillis()));
        player.closeInventory();
        String itemName = MarketGui.getItemName(new ItemStack(material));
        sendMessage(player, trc("prompt.purchase.total_price", NamedTextColor.YELLOW, itemName, amount));
    }

    public void handlePendingPurchasePricePrompt(Player player, String input) {
        PurchaseTotalPricePrompt prompt = pendingPurchaseTotalPricePrompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }

        String normalized = input == null ? "" : input.trim();
        if (isCancelKeyword(normalized)) {
            openPurchaseSetup(player, PurchaseSetupGui.Stage.AMOUNT, prompt.material(), prompt.amount(), prompt.searchQuery());
            return;
        }

        long totalPrice;
        try {
            totalPrice = parsePositiveLong(normalized);
        } catch (NumberFormatException e) {
            sendMessage(player, trc("error.invalid_total_price", NamedTextColor.RED));
            promptPurchaseTotalPrice(player, prompt.material(), prompt.amount(), prompt.searchQuery());
            return;
        }

        marketConfig.ensureLoaded();
        if (totalPrice < marketConfig.getMinPrice() || totalPrice > marketConfig.getMaxPrice()) {
            sendMessage(player, trc("error.total_price_range", NamedTextColor.RED,
                    marketConfig.getMinPrice(), marketConfig.getMaxPrice()));
            promptPurchaseTotalPrice(player, prompt.material(), prompt.amount(), prompt.searchQuery());
            return;
        }

        int activeOffers = marketData.countOffersByOwner(player.getUniqueId());
        if (activeOffers >= marketConfig.getHardListingCap()) {
            sendMessage(player, trc("error.market_offer_limit", NamedTextColor.RED, marketConfig.getHardListingCap()));
            return;
        }

        if (economy.get(player.getUniqueId()) < totalPrice) {
            sendMessage(player, trc("error.not_enough_funds_reserve", NamedTextColor.RED, totalPrice));
            openPurchaseSetup(player, PurchaseSetupGui.Stage.AMOUNT, prompt.material(), prompt.amount(), prompt.searchQuery());
            return;
        }

        ItemStack requested = new ItemStack(prompt.material(), prompt.amount());
        setPendingPurchaseOrder(player, new PendingPurchaseOrder(
                requested, totalPrice, System.currentTimeMillis()));

        new ConfirmationGui(this, player, ConfirmationGui.Type.PURCHASE_CREATE,
                0, MarketGui.getItemName(requested), requested.getAmount(), totalPrice, totalPrice, null, 0).open();
    }

    public MarketSellerMatch findMarketSeller(String input) {
        return findMarketOwner(input, MarketGui.DisplayMode.SELLING);
    }

    public MarketSellerMatch findMarketOwner(String input, MarketGui.DisplayMode displayMode) {
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }

        Map<UUID, String> sellers = new LinkedHashMap<>();
        if (displayMode == MarketGui.DisplayMode.PURCHASING) {
            for (MarketData.PurchaseOrder order : marketData.getAllPurchaseOrdersSorted()) {
                sellers.computeIfAbsent(order.buyer, this::resolveMarketSellerName);
            }
        } else {
            for (MarketData.Listing listing : marketData.getAllListingsSorted()) {
                sellers.computeIfAbsent(listing.seller, this::resolveMarketSellerName);
            }
        }

        MarketSellerMatch exactMatch = null;
        List<MarketSellerMatch> prefixMatches = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : sellers.entrySet()) {
            String sellerName = entry.getValue();
            if (sellerName == null) {
                continue;
            }

            String lowered = sellerName.toLowerCase(Locale.ROOT);
            if (lowered.equals(normalized)) {
                exactMatch = new MarketSellerMatch(entry.getKey(), sellerName);
                break;
            }
            if (lowered.startsWith(normalized)) {
                prefixMatches.add(new MarketSellerMatch(entry.getKey(), sellerName));
            }
        }

        if (exactMatch != null) {
            return exactMatch;
        }
        return prefixMatches.size() == 1 ? prefixMatches.get(0) : null;
    }

    public String resolveMarketSellerName(UUID sellerUuid) {
        Player online = Bukkit.getPlayer(sellerUuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(sellerUuid).getName();
        return name != null ? name : sellerUuid.toString();
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

    public PendingPurchaseOrder getPendingPurchaseOrder(Player player) {
        return pendingPurchaseOrders.get(player.getUniqueId());
    }

    public void setPendingPurchaseOrder(Player player, PendingPurchaseOrder order) {
        pendingPurchaseOrders.put(player.getUniqueId(), order);
    }

    // ── HUD ──

    public void setHudHidden(Player player, boolean hidden) {
        if (hidden) {
            hudHidden.add(player.getUniqueId());
            BossBar bar = playerBossBars.remove(player.getUniqueId());
            if (bar != null) {
                hideBossBar(player, bar);
            }
        } else {
            hudHidden.remove(player.getUniqueId());
        }
    }

    public void sendMessage(CommandSender sender, Component component) {
        adventure.sendMessage(sender, component);
    }

    public void sendActionBar(Player player, Component component) {
        adventure.sendActionBar(player, component);
    }

    public void showBossBar(Player player, BossBar bossBar) {
        adventure.showBossBar(player, bossBar);
    }

    public void hideBossBar(Player player, BossBar bossBar) {
        adventure.hideBossBar(player, bossBar);
    }

    public PlatformScheduler getSchedulerAdapter() {
        return scheduler;
    }

    public boolean isFolia() {
        return scheduler != null && scheduler.isFolia();
    }

    public boolean isTradeSupported() {
        return !isFolia();
    }

    public void refreshAllMarketGuis() {
        MarketGui[] snapshot = openMarketGuis.values().toArray(new MarketGui[0]);
        for (MarketGui gui : snapshot) {
            Player viewer = gui.getViewer();
            if (viewer != null && viewer.isOnline()) {
                scheduler.runPlayer(viewer, gui::refresh);
            }
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
    public MarketDeliveryData getMarketDeliveries() { return marketDeliveries; }
    public TradeManager getTradeManager() { return tradeManager; }

    public enum MarketFilterPromptType {
        ITEM_QUERY,
        SELLER
    }

    public record MarketFilterPrompt(MarketFilterPromptType type, MarketGui.ViewOptions viewOptions) {}

    public record PurchaseBlockSearchPrompt(Material selectedMaterial, int amount, String searchQuery, long createdAt) {}

    public record PurchaseTotalPricePrompt(Material material, int amount, String searchQuery, long createdAt) {}

    public record MarketSellerMatch(UUID uuid, String name) {}

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

    public int countMatchingRequestedItems(Player player, ItemStack requestedStack) {
        if (requestedStack == null || requestedStack.getType().isAir()) {
            return 0;
        }

        int total = 0;
        ItemStack probe = requestedStack.clone();
        probe.setAmount(1);
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir() && item.isSimilar(probe)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public void handlePurchaseOrderCreateConfirm(Player player, boolean confirmed) {
        PendingPurchaseOrder pending = pendingPurchaseOrders.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }

        if (!confirmed) {
            sendMessage(player, trc("purchase_order.cancelled", NamedTextColor.YELLOW));
            return;
        }

        long balance = economy.get(player.getUniqueId());
        if (balance < pending.totalPrice) {
            sendMessage(player, trc("error.not_enough_funds_reserve", NamedTextColor.RED, pending.totalPrice));
            return;
        }

        MarketData.PurchaseOrder order = marketData.addPurchaseOrder(player.getUniqueId(), pending.stack,
                pending.totalPrice, System.currentTimeMillis());
        economy.add(player.getUniqueId(), -order.totalPrice);
        transactionHistory.record(player.getUniqueId(), "PURCHASE_RESERVE", MarketGui.getItemName(order.stack),
                order.stack.getAmount(), -order.totalPrice, null);

        MarketNotifyData notifyState = marketNotify;
        long version = notifyState.bump();
        for (Player online : Bukkit.getOnlinePlayers()) {
            notifyState.markSeen(online.getUniqueId(), version);
        }

        sendMessage(player, trc("purchase_order.created", NamedTextColor.GREEN, order.id,
                MarketGui.getItemName(order.stack), order.stack.getAmount(), order.totalPrice));
        refreshAllMarketGuis();
    }

    public void cancelPurchaseOrder(Player player, int orderId) {
        Optional<MarketData.PurchaseOrder> removedOpt = marketData.removePurchaseOrder(orderId);
        if (removedOpt.isEmpty()) {
            sendMessage(player, trc("purchase_order.missing", NamedTextColor.RED));
            return;
        }

        MarketData.PurchaseOrder order = removedOpt.get();
        if (!order.buyer.equals(player.getUniqueId())) {
            marketData.putBackPurchaseOrder(order);
            sendMessage(player, trc("purchase_order.cancel_own_only", NamedTextColor.RED));
            return;
        }

        refundPurchaseOrder(player, order);
    }

    private void refundPurchaseOrder(Player player, MarketData.PurchaseOrder order) {
        economy.add(player.getUniqueId(), order.totalPrice);
        transactionHistory.record(player.getUniqueId(), "PURCHASE_REFUND", MarketGui.getItemName(order.stack),
                order.stack.getAmount(), order.totalPrice, null);
        sendMessage(player, trc("purchase_order.refunded", NamedTextColor.GREEN, order.totalPrice));
        refreshAllMarketGuis();
    }

    public void handlePurchaseOrderSaleConfirm(Player seller, boolean confirmed, int orderId) {
        if (!confirmed) {
            return;
        }

        Optional<MarketData.PurchaseOrder> removedOpt = marketData.removePurchaseOrder(orderId);
        if (removedOpt.isEmpty()) {
            sendMessage(seller, trc("purchase_order.already_closed", NamedTextColor.RED));
            return;
        }

        MarketData.PurchaseOrder order = removedOpt.get();
        if (order.buyer.equals(seller.getUniqueId())) {
            refundPurchaseOrder(seller, order);
            return;
        }

        int available = countMatchingRequestedItems(seller, order.stack);
        if (available < order.stack.getAmount()) {
            marketData.putBackPurchaseOrder(order);
            sendMessage(seller, trc("purchase_order.need_matching_items", NamedTextColor.RED, order.stack.getAmount()));
            return;
        }

        int accepted = order.stack.getAmount();
        long total = order.totalPrice;
        if (total <= 0) {
            marketData.putBackPurchaseOrder(order);
            sendMessage(seller, trc("purchase_order.cannot_complete", NamedTextColor.RED));
            return;
        }

        removeMatchingItems(seller, order.stack, accepted);
        economy.add(seller.getUniqueId(), total);
        marketData.addSale(total);

        ItemStack delivered = order.stack.clone();
        delivered.setAmount(accepted);
        marketDeliveries.add(order.buyer, delivered);

        Player buyerOnline = Bukkit.getPlayer(order.buyer);
        if (buyerOnline != null) {
            sendMessage(buyerOnline, trc("purchase_order.buyer_filled", NamedTextColor.GREEN,
                    MarketGui.getItemName(delivered), accepted, total));
            deliverQueuedItems(buyerOnline);
            if (marketDeliveries.has(order.buyer)) {
                sendMessage(buyerOnline, trc("purchase_order.delivery_waiting", NamedTextColor.RED));
            }
        }

        sendMessage(seller, trc("purchase_order.seller_filled", NamedTextColor.GREEN,
                MarketGui.getItemName(delivered), accepted, total));

        String buyerName = resolveMarketSellerName(order.buyer);
        transactionHistory.record(seller.getUniqueId(), "SELL_TO_ORDER", MarketGui.getItemName(delivered),
                accepted, total, buyerName);
        transactionHistory.record(order.buyer, "PURCHASE_FILL", MarketGui.getItemName(delivered),
                accepted, -total, seller.getName());
        refreshAllMarketGuis();
    }

    public void deliverQueuedItems(Player player) {
        List<ItemStack> queued = marketDeliveries.get(player.getUniqueId());
        if (queued.isEmpty()) {
            return;
        }

        List<ItemStack> remaining = new ArrayList<>();
        int deliveredAmount = 0;
        for (ItemStack stack : queued) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack.clone());
            deliveredAmount += stack.getAmount();
            if (!leftover.isEmpty()) {
                for (ItemStack item : leftover.values()) {
                    deliveredAmount -= item.getAmount();
                    remaining.add(item.clone());
                }
            }
        }

        marketDeliveries.set(player.getUniqueId(), remaining);
        if (deliveredAmount > 0) {
            sendMessage(player, trc("market.delivery.received", NamedTextColor.YELLOW, deliveredAmount));
        }
    }

    private void removeMatchingItems(Player player, ItemStack requestedStack, int amount) {
        if (amount <= 0) {
            return;
        }

        ItemStack probe = requestedStack.clone();
        probe.setAmount(1);
        int remaining = amount;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir() || !item.isSimilar(probe)) {
                continue;
            }

            int removed = Math.min(item.getAmount(), remaining);
            int updated = item.getAmount() - removed;
            if (updated <= 0) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(updated);
            }
            remaining -= removed;
        }
    }

    // ── Trade Confirmation Handlers ──

    public void handleSellConfirm(Player player, boolean confirmed) {
        PendingSale pending = pendingSales.remove(player.getUniqueId());
        if (pending == null) return;

        if (!confirmed) {
            sendMessage(player, trc("listing.cancelled", NamedTextColor.YELLOW));
            return;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (Items.isEmpty(inHand) || !inHand.isSimilar(pending.stack) || inHand.getAmount() < pending.stack.getAmount()) {
            sendMessage(player, trc("error.item_changed_cancelled", NamedTextColor.RED));
            return;
        }

        if (economy.get(player.getUniqueId()) < pending.fee) {
            sendMessage(player, trc("error.not_enough_funds_fee", NamedTextColor.RED, pending.fee));
            return;
        }

        // Remove item from hand
        int removeCount = pending.stack.getAmount();
        if (inHand.getAmount() == removeCount) {
            player.getInventory().setItemInMainHand(Items.empty());
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
        sendMessage(player, trc("listing.created", NamedTextColor.GREEN, listing.id, pending.price, pending.fee));
        refreshAllMarketGuis();

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
                    Items.isEmpty(pending.stack) ? 0 : pending.stack.getAmount(),
                    0, 0L, 0L, 0, 0,
                    economy.get(player.getUniqueId()), economy.get(player.getUniqueId()),
                    "CANCELLED", "user_cancel");
            sendMessage(player, trc("common.operation_cancelled", NamedTextColor.YELLOW));
            return;
        }

        bankConfig.ensureLoaded();
        MarketBankConfig.BankResource resource = bankConfig.get(pending.itemId);
        if (resource == null) {
            sendMessage(player, trc("error.resource_unavailable", NamedTextColor.RED));
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
        String inHandId = Items.isEmpty(inHand) ? "" : MarketBankConfig.toMinecraftId(inHand.getType());
        if (Items.isEmpty(inHand) || !inHandId.equals(pending.itemId)) {
            sendMessage(player, trc("error.item_changed_cancelled", NamedTextColor.RED));
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
            sendMessage(player, trc("bank.sell_limit_reached", NamedTextColor.RED));
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
            player.getInventory().setItemInMainHand(Items.empty());
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
            sendMessage(player, trc("bank.sell_partial", NamedTextColor.YELLOW, accepted, pending.requested));
        } else {
            sendMessage(player, trc("bank.sell_success", NamedTextColor.GREEN, accepted, total));
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
            sendMessage(player, trc("bank.buy_limit_reached", NamedTextColor.RED));
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
            sendMessage(player, trc("error.not_enough_funds", NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "buyfrom", pending.itemId, pending.itemName,
                    pending.requested, 0, 0, pending.price, 0L,
                    buyLimit, remaining, balance, balance,
                    "REJECTED", "no_money");
            return;
        }

        Material mat = MarketBankConfig.toMaterial(pending.itemId);
        if (mat == null) {
            sendMessage(player, trc("error.resource_unavailable", NamedTextColor.RED));
            return;
        }

        int space = getMaxInsertable(player, new ItemStack(mat, 1));
        if (space <= 0) {
            sendMessage(player, trc("error.no_inventory_space", NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "buyfrom", pending.itemId, pending.itemName,
                    pending.requested, 0, 0, pending.price, 0L,
                    buyLimit, remaining, balance, balance,
                    "REJECTED", "no_space");
            return;
        }

        int accepted = Math.min(pending.accepted, Math.min(remaining, Math.min(affordable, space)));
        if (accepted <= 0) {
            sendMessage(player, trc("error.purchase_cannot_complete", NamedTextColor.RED));
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
            sendMessage(player, trc("bank.buy_partial", NamedTextColor.YELLOW, accepted, pending.requested));
        } else {
            sendMessage(player, trc("bank.buy_success", NamedTextColor.GREEN, accepted, total));
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
            sendMessage(player, trc("listing.already_sold", NamedTextColor.RED));
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
            sendMessage(player, trc("listing.removed_from_sale", NamedTextColor.GREEN));
            refreshAllMarketGuis();
            return;
        }

        long balance = economy.get(player.getUniqueId());
        if (balance < listing.price) {
            marketData.putBack(listing);
            sendMessage(player, trc("error.not_enough_funds", NamedTextColor.RED));
            return;
        }

        if (!canFitInInventory(player, listing.stack)) {
            marketData.putBack(listing);
            sendMessage(player, trc("error.no_inventory_space", NamedTextColor.RED));
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

        sendMessage(player, trc("listing.purchase_completed", NamedTextColor.GREEN, itemName, count, listing.price));

        Player sellerOnline = Bukkit.getPlayer(listing.seller);
        if (sellerOnline != null) {
            sendMessage(sellerOnline, trc("listing.seller_notified", NamedTextColor.GREEN, itemName, count, listing.price));
        }

        String sellerName = sellerOnline != null ? sellerOnline.getName() : listing.seller.toString();
        transactionHistory.record(player.getUniqueId(), "BUY", itemName, count, -listing.price, sellerName);
        transactionHistory.record(listing.seller, "SELL_INCOME", itemName, count, listing.price, player.getName());

        refreshAllMarketGuis();
    }

    private boolean canFitInInventory(Player player, ItemStack stack) {
        if (stack == null || Items.isEmpty(stack)) return true;
        int remaining = stack.getAmount();
        int maxPerStack = stack.getMaxStackSize();
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot == null || Items.isEmpty(slot)) {
                remaining -= maxPerStack;
            } else if (slot.isSimilar(stack)) {
                remaining -= (maxPerStack - slot.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private int getMaxInsertable(Player player, ItemStack stack) {
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

    // ── Pending Trade Records ──

    public record PendingSale(ItemStack stack, long price, long fee, long createdAt) {}

    public record PendingPurchaseOrder(ItemStack stack, long totalPrice, long createdAt) {}

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

    private long parsePositiveLong(String raw) {
        long value = Long.parseLong(raw);
        if (value <= 0) {
            throw new NumberFormatException();
        }
        return value;
    }

    public static EasyTradingPlugin getInstance() {
        return instance;
    }

    public LocalizationManager getLocalization() {
        return localization;
    }

    public String tr(String key, Object... args) {
        return localization.tr(key, args);
    }

    public Component trc(String key, NamedTextColor color, Object... args) {
        return Component.text(tr(key, args)).color(color);
    }

    public boolean isCancelKeyword(String input) {
        return matchesKeyword(input, CANCEL_KEYWORDS);
    }

    public boolean isClearKeyword(String input) {
        return matchesKeyword(input, CLEAR_KEYWORDS);
    }

    private boolean matchesKeyword(String input, Set<String> keywords) {
        if (input == null) {
            return false;
        }
        return keywords.contains(input.trim().toLowerCase(Locale.ROOT));
    }
}



