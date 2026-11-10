package com.easytrading.paper;

import com.easytrading.paper.command.MarketCommand;
import com.easytrading.paper.data.*;
import com.easytrading.paper.gui.ConfirmationGui;
import com.easytrading.paper.gui.MarketGui;
import com.easytrading.paper.gui.PurchaseSetupGui;
import com.easytrading.paper.gui.PlayerConfigGui;
import com.easytrading.paper.listener.PlayerListener;
import com.easytrading.paper.locale.LocalizationManager;
import com.easytrading.paper.platform.PlatformScheduler;
import com.easytrading.paper.trade.TradeManager;
import com.easytrading.paper.update.UpdateChecker;
import com.easytrading.paper.util.AdventureSupport;
import com.easytrading.paper.util.Items;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EasyTradingPlugin extends JavaPlugin {

    private static EasyTradingPlugin instance;
    private NamespacedKey creativeOriginKey;

    private EconomyData economy;
    private MarketData marketData;
    private MarketConfig marketConfig;
    private MarketBankConfig bankConfig;
    private MarketBankState bankState;
    private TransactionHistoryData transactionHistory;
    private MarketNotifyData marketNotify;
    private MarketDeliveryData marketDeliveries;
    private HudPreferencesData hudPreferences;

    private final Map<UUID, PendingSale> pendingSales = new ConcurrentHashMap<>();
    private final Map<UUID, PendingBankTrade> pendingBank = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPurchaseOrder> pendingPurchaseOrders = new ConcurrentHashMap<>();
    private final Map<UUID, PurchaseItemSearchPrompt> pendingPurchaseItemSearchPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, PurchaseAmountPrompt> pendingPurchaseAmountPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, PurchaseTotalPricePrompt> pendingPurchaseTotalPricePrompts = new ConcurrentHashMap<>();
    private final Map<UUID, MarketFilterPrompt> pendingMarketPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, MarketGui> openMarketGuis = new ConcurrentHashMap<>();
    private final Map<UUID, PurchaseSetupGui> openPurchaseSetupGuis = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerConfigGui> openPlayerConfigGuis = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmationGui> openConfirmations = new ConcurrentHashMap<>();
    private final Set<UUID> hudHidden = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> playerBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, PlatformScheduler.TaskHandle> playerHudTasks = new ConcurrentHashMap<>();
    private VaultEconomyProvider vaultProvider;
    private TradeManager tradeManager;
    private AdventureSupport adventure;
    private PlatformScheduler scheduler;
    private LocalizationManager localization;
    private volatile UpdateChecker.Release availableUpdate;

    private static final long PENDING_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final Set<String> CANCEL_KEYWORDS = Set.of("cancel", "отмена", "отменить");
    private static final Set<String> BACK_KEYWORDS = Set.of("back", "назад");
    private static final Set<String> CLEAR_KEYWORDS = Set.of("clear", "очистить", "сброс", "reset");
    private PlatformScheduler.TaskHandle saveTask;
    private PlatformScheduler.TaskHandle cleanupTask;
    private PlatformScheduler.TaskHandle tradeCleanupTask;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        ensureConfigurationDefaults();
        new Metrics(this, 35237);
        creativeOriginKey = new NamespacedKey(this, "creative-origin");
        Path dataFolder = getDataFolder().toPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            getLogger().severe("Could not create data folder: " + e);
        }

        economy = new EconomyData(dataFolder, getLogger());
        marketData = new MarketData(dataFolder, getLogger());
        marketConfig = new MarketConfig(dataFolder);
        bankConfig = new MarketBankConfig(dataFolder);
        bankState = new MarketBankState(dataFolder);
        transactionHistory = new TransactionHistoryData(dataFolder);
        marketNotify = new MarketNotifyData(dataFolder);
        marketDeliveries = new MarketDeliveryData(dataFolder);
        hudPreferences = new HudPreferencesData(dataFolder);

        marketConfig.ensureLoaded();
        bankConfig.ensureLoaded();

        localization = new LocalizationManager();
        localization.loadInternalBundle(this, "en_us", "lang/en_us.properties");

        adventure = new AdventureSupport(this);
        adventure.enable();
        scheduler = new PlatformScheduler(this);
        tradeManager = new TradeManager(this);
        checkForUpdates();

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

        // Vault is a soft dependency. Do not load VaultEconomyProvider when the
        // Vault API is absent, otherwise the JVM cannot resolve its Economy type.
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

        // Trades hold real item stacks in virtual inventories. Return/queue
        // every escrow before Bukkit tears down players and inventories, then
        // persist the queue in saveAll below. Without this, a reload/shutdown
        // could permanently destroy offered items.
        if (tradeManager != null) {
            tradeManager.shutdown();
        }
        for (PlatformScheduler.TaskHandle handle : playerHudTasks.values()) {
            handle.cancel();
        }
        playerHudTasks.clear();

        // Unregister Vault provider
        if (vaultProvider != null) {
            Bukkit.getServicesManager().unregisterAll(this);
        }

        // Remove every balance HUD element owned by this plugin.
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearBalanceHud(player);
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
        hudPreferences.save();
    }

    private void ensureConfigurationDefaults() {
        FileConfiguration config = getConfig();
        boolean changed = false;
        if (!config.isSet("hud.mode")) {
            config.set("hud.mode", "tab-footer");
            changed = true;
        }
        if (!config.isSet("hud.number-format")) {
            config.set("hud.number-format", "grouped");
            changed = true;
        }
        if (!config.isSet("trade.request-mode")) {
            config.set("trade.request-mode", "all");
            changed = true;
        }
        if (!config.isSet("updates.enabled")) {
            config.set("updates.enabled", true);
            changed = true;
        }
        if (!config.isSet("updates.notify-admins")) {
            config.set("updates.notify-admins", true);
            changed = true;
        }
        if (changed) {
            saveConfig();
        }
    }

    private void checkForUpdates() {
        if (!getConfig().getBoolean("updates.enabled", true)) {
            return;
        }
        String installedVersion = getDescription().getVersion();
        new UpdateChecker().findNewerRelease(installedVersion).whenComplete((result, failure) -> {
            if (!isEnabled()) return;
            if (failure != null) {
                getLogger().fine("Could not check for EasyTrading updates: " + failure.getMessage());
                return;
            }
            result.ifPresent(update -> {
                availableUpdate = update;
                getLogger().warning("A new EasyTrading version is available: " + update.version()
                        + " (installed: " + installedVersion + "). Download: " + update.pageUrl());
            });
        });
    }

    private void tickPlayerHud(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        bankState.ensureToday();
        bankConfig.ensureLoaded();

        UUID uuid = player.getUniqueId();
        renderBalanceHud(player, hudHidden.contains(uuid));

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

    private void renderBalanceHud(Player player, boolean hidden) {
        HudMode mode = getPlayerHudMode(player);
        if (hidden || mode == HudMode.NONE) {
            clearBalanceHud(player);
            return;
        }

        Component balance = Component.text(tr("hud.balance", formatBalance(player, economy.get(player.getUniqueId()))))
                .color(NamedTextColor.GOLD);
        if (mode == HudMode.TAB_FOOTER) {
            removeBossBar(player);
            // The footer is visible only while the player holds Tab. The
            // header remains available for server-list plugins to manage.
            player.setPlayerListFooter("\n" + AdventureSupport.legacy(balance) + "\n");
            return;
        }

        player.setPlayerListFooter(null);
        BossBar bar = playerBossBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(balance, 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            playerBossBars.put(player.getUniqueId(), bar);
            showBossBar(player, bar);
        } else {
            bar.name(balance);
        }
    }

    public HudMode getPlayerHudMode(Player player) {
        if (player != null && hudPreferences != null) {
            Optional<String> saved = hudPreferences.getHudMode(player.getUniqueId());
            if (saved.isPresent()) return HudMode.fromConfig(saved.get());
        }
        return HudMode.fromConfig(getConfig().getString("hud.mode", "tab-footer"));
    }

    public void setPlayerHudMode(Player player, HudMode mode) {
        if (player == null || mode == null || mode == HudMode.NONE) return;
        hudPreferences.setHudMode(player.getUniqueId(), mode.configValue());
        hudPreferences.save();
        renderBalanceHud(player, hudHidden.contains(player.getUniqueId()));
    }

    public NumberFormatMode getPlayerNumberFormat(Player player) {
        if (player != null && hudPreferences != null) {
            Optional<String> saved = hudPreferences.getNumberFormat(player.getUniqueId());
            if (saved.isPresent()) return NumberFormatMode.fromConfig(saved.get());
        }
        return NumberFormatMode.fromConfig(getConfig().getString("hud.number-format", "grouped"));
    }

    public void setPlayerNumberFormat(Player player, NumberFormatMode format) {
        if (player == null || format == null) return;
        hudPreferences.setNumberFormat(player.getUniqueId(), format.configValue());
        hudPreferences.save();
        renderBalanceHud(player, hudHidden.contains(player.getUniqueId()));
    }

    public String formatBalance(Player player, long value) {
        return getPlayerNumberFormat(player).format(value);
    }

    public TradeRequestMode getTradeRequestMode(Player player) {
        if (player != null && hudPreferences != null) {
            Optional<String> saved = hudPreferences.getTradeRequestMode(player.getUniqueId());
            if (saved.isPresent()) return TradeRequestMode.fromConfig(saved.get());
        }
        return TradeRequestMode.fromConfig(getConfig().getString("trade.request-mode", "all"));
    }

    public void setTradeRequestMode(Player player, TradeRequestMode mode) {
        if (player == null || mode == null) return;
        hudPreferences.setTradeRequestMode(player.getUniqueId(), mode.configValue());
        hudPreferences.save();
    }

    public boolean canReceiveTradeRequest(Player target, Player sender) {
        if (target == null || sender == null) return false;
        TradeRequestMode mode = getTradeRequestMode(target);
        if (mode == TradeRequestMode.NONE) return false;
        if (mode == TradeRequestMode.ALL) return true;
        if (!target.getWorld().equals(sender.getWorld())) return false;
        return target.getLocation().distanceSquared(sender.getLocation()) <= (double) mode.radius() * mode.radius();
    }

    private void clearBalanceHud(Player player) {
        player.setPlayerListFooter(null);
        removeBossBar(player);
    }

    private void removeBossBar(Player player) {
        BossBar bar = playerBossBars.remove(player.getUniqueId());
        if (bar != null) {
            hideBossBar(player, bar);
        }
    }

    private void cleanupStalePending() {
        long now = System.currentTimeMillis();
        pendingSales.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingBank.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingPurchaseOrders.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingPurchaseItemSearchPrompts.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingPurchaseAmountPrompts.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
        pendingPurchaseTotalPricePrompts.entrySet().removeIf(e -> now - e.getValue().createdAt > PENDING_TIMEOUT_MS);
    }

    // ── Player Events ──

    public void onPlayerLogin(Player player) {
        syncBalance(player);

        UpdateChecker.Release update = availableUpdate;
        if (update != null && getConfig().getBoolean("updates.notify-admins", true)
                && player.hasPermission("easytrading.admin")) {
            sendMessage(player, trc("update.available", NamedTextColor.YELLOW,
                    update.version(), getDescription().getVersion(), update.pageUrl()));
        }

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
        pendingPurchaseItemSearchPrompts.remove(uuid);
        pendingPurchaseAmountPrompts.remove(uuid);
        pendingPurchaseTotalPricePrompts.remove(uuid);
        pendingMarketPrompts.remove(uuid);
        openMarketGuis.remove(uuid);
        openPurchaseSetupGuis.remove(uuid);
        openPlayerConfigGuis.remove(uuid);
        openConfirmations.remove(uuid);
        tradeManager.handleDisconnect(player);
        stopPlayerHudTask(player);
        clearBalanceHud(player);
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

    public void openPlayerConfig(Player player) {
        PlayerConfigGui gui = new PlayerConfigGui(this, player);
        openPlayerConfigGuis.put(player.getUniqueId(), gui);
        gui.open();
    }

    public PlayerConfigGui getOpenPlayerConfigGui(Player player) {
        return openPlayerConfigGuis.get(player.getUniqueId());
    }

    public void removePlayerConfigGui(Player player) {
        openPlayerConfigGuis.remove(player.getUniqueId());
    }

    public void openPurchaseSetup(Player player) {
        promptPurchaseItemSearch(player);
    }

    public void openPurchaseSetup(Player player, String query) {
        PurchaseSetupGui gui = new PurchaseSetupGui(this, player, query);
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

    public boolean hasPendingPurchaseItemSearchPrompt(Player player) {
        return pendingPurchaseItemSearchPrompts.containsKey(player.getUniqueId());
    }

    public boolean hasPendingPurchaseAmountPrompt(Player player) {
        return pendingPurchaseAmountPrompts.containsKey(player.getUniqueId());
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

    public void promptPurchaseItemSearch(Player player) {
        promptPurchaseItemSearch(player, null);
    }

    public void promptPurchaseItemSearch(Player player, String previousQuery) {
        pendingPurchaseItemSearchPrompts.put(player.getUniqueId(), new PurchaseItemSearchPrompt(System.currentTimeMillis()));
        player.closeInventory();
        String key = previousQuery == null || previousQuery.isBlank()
                ? "prompt.purchase.item_search" : "prompt.purchase.item_search_again";
        sendMessage(player, trc(key, NamedTextColor.YELLOW, previousQuery));
    }

    public void handlePendingPurchaseItemSearchPrompt(Player player, String input) {
        PurchaseItemSearchPrompt prompt = pendingPurchaseItemSearchPrompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        String normalized = input == null ? "" : input.trim();
        if (isCancelKeyword(normalized) || normalized.isBlank()) {
            cancelPurchaseSetup(player);
            return;
        }
        openPurchaseSetup(player, normalized);
    }

    public void promptPurchaseAmount(Player player, Material material, String searchQuery) {
        if (material == null || !material.isItem() || material.isAir()) {
            promptPurchaseItemSearch(player);
            return;
        }
        int maxAmount = MarketData.getPurchaseOrderAmountLimit(new ItemStack(material));
        if (maxAmount == 1) {
            promptPurchaseTotalPrice(player, material, 1, searchQuery);
            return;
        }
        pendingPurchaseAmountPrompts.put(player.getUniqueId(),
                new PurchaseAmountPrompt(material, searchQuery, System.currentTimeMillis()));
        player.closeInventory();
        sendMessage(player, trc("prompt.purchase.amount", NamedTextColor.YELLOW,
                getLocalization().itemName(material), maxAmount));
    }

    public void handlePendingPurchaseAmountPrompt(Player player, String input) {
        PurchaseAmountPrompt prompt = pendingPurchaseAmountPrompts.remove(player.getUniqueId());
        if (prompt == null) return;
        String normalized = input == null ? "" : input.trim();
        if (isCancelKeyword(normalized)) {
            cancelPurchaseSetup(player);
            return;
        }
        if (isBackKeyword(normalized)) {
            openPurchaseSetup(player, prompt.searchQuery());
            return;
        }
        final int amount;
        try {
            amount = parsePurchaseAmount(normalized, prompt.material());
        } catch (NumberFormatException ignored) {
            sendMessage(player, trc("error.invalid_purchase_amount", NamedTextColor.RED,
                    MarketData.getPurchaseOrderAmountLimit(new ItemStack(prompt.material()))));
            promptPurchaseAmount(player, prompt.material(), prompt.searchQuery());
            return;
        }
        promptPurchaseTotalPrice(player, prompt.material(), amount, prompt.searchQuery());
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
            cancelPurchaseSetup(player);
            return;
        }
        if (isBackKeyword(normalized)) {
            if (MarketData.getPurchaseOrderAmountLimit(new ItemStack(prompt.material())) == 1) {
                openPurchaseSetup(player, prompt.searchQuery());
            } else {
                promptPurchaseAmount(player, prompt.material(), prompt.searchQuery());
            }
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
            promptPurchaseAmount(player, prompt.material(), prompt.searchQuery());
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

    /** Whether Creative-origin markers are enforced for market and bank sales. */
    public boolean isCreativeItemBlockingEnabled() {
        return getConfig().getBoolean("market.block-creative-items", true);
    }

    /** Return a copy marked as having passed through a Creative-mode inventory. */
    public ItemStack markCreativeOrigin(ItemStack stack) {
        if (Items.isEmpty(stack) || creativeOriginKey == null) return stack;
        ItemStack marked = stack.clone();
        ItemMeta meta = marked.getItemMeta();
        meta.getPersistentDataContainer().set(creativeOriginKey, PersistentDataType.BYTE, (byte) 1);
        marked.setItemMeta(meta);
        return marked;
    }

    public boolean isCreativeOrigin(ItemStack stack) {
        if (!isCreativeItemBlockingEnabled() || Items.isEmpty(stack) || creativeOriginKey == null) return false;
        ItemMeta meta = stack.getItemMeta();
        Byte marker = meta.getPersistentDataContainer().get(creativeOriginKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public boolean mustBlockMarketSale(Player player, ItemStack stack) {
        return isCreativeItemBlockingEnabled()
                && (isCreativeOrigin(stack) || (player != null
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)));
    }

    // ── HUD ──

    public void setHudHidden(Player player, boolean hidden) {
        if (hidden) {
            hudHidden.add(player.getUniqueId());
            clearBalanceHud(player);
        } else {
            hudHidden.remove(player.getUniqueId());
            renderBalanceHud(player, false);
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

    public enum HudMode {
        TAB_FOOTER,
        BOSS_BAR,
        NONE;

        public String configValue() {
            return switch (this) {
                case TAB_FOOTER -> "tab-footer";
                case BOSS_BAR -> "boss-bar";
                case NONE -> "none";
            };
        }

        private static HudMode fromConfig(String value) {
            if (value == null) return TAB_FOOTER;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "boss-bar", "bossbar" -> BOSS_BAR;
                case "none", "off", "disabled" -> NONE;
                default -> TAB_FOOTER;
            };
        }
    }

    public enum NumberFormatMode {
        GROUPED("grouped"),
        COMPACT("compact");

        private final String configValue;

        NumberFormatMode(String configValue) { this.configValue = configValue; }
        public String configValue() { return configValue; }
        public String example() { return format(12_450); }
        public String format(long value) {
            if (this == GROUPED) return String.format(Locale.ROOT, "%,d", value);
            double absolute = Math.abs((double) value);
            if (absolute < 1_000) return Long.toString(value);
            String suffix;
            double divisor;
            if (absolute >= 1_000_000_000) { suffix = "B"; divisor = 1_000_000_000d; }
            else if (absolute >= 1_000_000) { suffix = "M"; divisor = 1_000_000d; }
            else { suffix = "K"; divisor = 1_000d; }
            double scaled = value / divisor;
            return String.format(Locale.ROOT, scaled >= 100 ? "%.0f%s" : "%.1f%s", scaled, suffix);
        }
        private static NumberFormatMode fromConfig(String value) {
            return "compact".equalsIgnoreCase(value) ? COMPACT : GROUPED;
        }
    }

    public enum TradeRequestMode {
        ALL("all", 0), RADIUS_50("radius-50", 50), RADIUS_100("radius-100", 100),
        RADIUS_150("radius-150", 150), NONE("none", 0);

        private final String configValue;
        private final int radius;
        TradeRequestMode(String configValue, int radius) { this.configValue = configValue; this.radius = radius; }
        public String configValue() { return configValue; }
        public int radius() { return radius; }
        private static TradeRequestMode fromConfig(String value) {
            if (value == null) return ALL;
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "radius-50" -> RADIUS_50;
                case "radius-100" -> RADIUS_100;
                case "radius-150" -> RADIUS_150;
                case "none" -> NONE;
                default -> ALL;
            };
        }
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

    public record PurchaseItemSearchPrompt(long createdAt) {}

    public record PurchaseAmountPrompt(Material material, String searchQuery, long createdAt) {}

    public record PurchaseTotalPricePrompt(Material material, int amount, String searchQuery, long createdAt) {}

    public record MarketSellerMatch(UUID uuid, String name) {}

    // ── Fee Computation ──

    public long computeFee(UUID seller, long price) {
        marketConfig.ensureLoaded();
        if (price <= 0) {
            return 0L;
        }
        int count = marketData.countBySeller(seller);
        if (count < marketConfig.getFreeListingSlots()) return 0L;
        int overload = Math.max(0, count - marketConfig.getSoftListingCap() + 1);
        long feePercent = (long) marketConfig.getBaseFeePercent()
                + (long) overload * marketConfig.getProgressiveFeePercent();
        long rawFee = percentageAmount(price, feePercent, RoundingMode.HALF_UP);
        long withMin = Math.max(marketConfig.getFeeMin(), rawFee);
        return Math.min(marketConfig.getFeeMax(), withMin);
    }

    /** Calculates the one-time promoted listing fee, rounded up to whole coins. */
    public long computePromotionFee(long price) {
        marketConfig.ensureLoaded();
        int percent = marketConfig.getPromotionFeePercent();
        if (price <= 0 || percent <= 0) {
            return 0L;
        }
        return percentageAmount(price, percent, RoundingMode.CEILING);
    }

    /** Calculate a positive percentage without floating-point overflow. */
    private long percentageAmount(long value, long percent, RoundingMode roundingMode) {
        if (value <= 0 || percent <= 0) {
            return 0L;
        }
        BigDecimal result = BigDecimal.valueOf(value)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100L), 0, roundingMode);
        return result.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE
                : result.longValue();
    }

    /**
     * Withdraws a market fee through Vault when available. EasyTrading owns the
     * underlying economy, so servers without Vault use the same EconomyData
     * storage directly and keep Vault an optional dependency.
     */
    private boolean withdrawMarketFee(Player player, long amount) {
        if (amount < 0) {
            return false;
        }
        if (vaultProvider != null) {
            return vaultProvider.withdrawPlayer(player, amount).transactionSuccess();
        }
        return economy.tryWithdraw(player.getUniqueId(), amount)
                == EconomyData.MutationResult.SUCCESS;
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

        if (pending.stack == null || Items.isEmpty(pending.stack)
                || pending.stack.getAmount() <= 0 || pending.totalPrice <= 0
                || pending.stack.getAmount() > MarketData.getPurchaseOrderAmountLimit(pending.stack)) {
            sendMessage(player, trc("purchase_order.cannot_complete", NamedTextColor.RED));
            return;
        }

        marketConfig.ensureLoaded();
        if (pending.totalPrice < marketConfig.getMinPrice()
                || pending.totalPrice > marketConfig.getMaxPrice()
                || marketData.countOffersByOwner(player.getUniqueId()) >= marketConfig.getHardListingCap()) {
            sendMessage(player, trc("error.market_offer_limit", NamedTextColor.RED,
                    marketConfig.getHardListingCap()));
            return;
        }

        long balance = economy.get(player.getUniqueId());
        if (balance < pending.totalPrice) {
            sendMessage(player, trc("error.not_enough_funds_reserve", NamedTextColor.RED, pending.totalPrice));
            return;
        }

        // Reserve the money atomically before publishing the order.  This
        // closes the check-then-debit race with commands/Vault consumers.
        EconomyData.MutationResult withdrawal = economy.tryWithdraw(player.getUniqueId(), pending.totalPrice);
        if (withdrawal != EconomyData.MutationResult.SUCCESS) {
            sendMessage(player, trc("error.not_enough_funds_reserve", NamedTextColor.RED, pending.totalPrice));
            return;
        }

        MarketData.PurchaseOrder order;
        try {
            order = marketData.addPurchaseOrder(player.getUniqueId(), pending.stack, pending.stack.getAmount(),
                    pending.totalPrice, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            // Never burn a reservation if publishing the order fails.
            economy.tryDeposit(player.getUniqueId(), pending.totalPrice);
            getLogger().warning("Could not create purchase order: " + ex.getMessage());
            sendMessage(player, trc("purchase_order.cannot_complete", NamedTextColor.RED));
            return;
        }
        transactionHistory.record(player.getUniqueId(), "PURCHASE_RESERVE", MarketGui.getItemName(order.stack),
                order.amount, -order.totalPrice, null);

        MarketNotifyData notifyState = marketNotify;
        long version = notifyState.bump();
        for (Player online : Bukkit.getOnlinePlayers()) {
            notifyState.markSeen(online.getUniqueId(), version);
        }

        sendMessage(player, trc("purchase_order.created", NamedTextColor.GREEN, order.id,
                MarketGui.getItemName(order.stack), order.amount, order.totalPrice));
        marketData.save();
        economy.save();
        transactionHistory.save();
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

        if (refundPurchaseOrder(player, order)) {
            marketData.save();
            economy.save();
            transactionHistory.save();
        } else {
            marketData.putBackPurchaseOrder(order);
        }
    }

    private boolean refundPurchaseOrder(Player player, MarketData.PurchaseOrder order) {
        if (order == null || order.totalPrice <= 0) {
            return false;
        }
        EconomyData.MutationResult deposit = economy.tryDeposit(player.getUniqueId(), order.totalPrice);
        if (deposit != EconomyData.MutationResult.SUCCESS) {
            sendMessage(player, trc("error.balance_overflow", NamedTextColor.RED));
            getLogger().warning("Could not refund purchase order " + order.id + " to " + player.getUniqueId());
            return false;
        }
        transactionHistory.record(player.getUniqueId(), "PURCHASE_REFUND", MarketGui.getItemName(order.stack),
                order.amount, order.totalPrice, null);
        sendMessage(player, trc("purchase_order.refunded", NamedTextColor.GREEN, order.totalPrice));
        refreshAllMarketGuis();
        return true;
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
            if (!refundPurchaseOrder(seller, order)) {
                marketData.putBackPurchaseOrder(order);
            }
            return;
        }

        if (order.stack == null || Items.isEmpty(order.stack) || order.amount <= 0
                || order.totalPrice <= 0) {
            marketData.putBackPurchaseOrder(order);
            sendMessage(seller, trc("purchase_order.cannot_complete", NamedTextColor.RED));
            return;
        }

        int available = countMatchingRequestedItems(seller, order.stack);
        if (available < order.amount) {
            marketData.putBackPurchaseOrder(order);
            sendMessage(seller, trc("purchase_order.need_matching_items", NamedTextColor.RED, order.amount));
            return;
        }

        int accepted = order.amount;
        long total = order.totalPrice;
        if (total <= 0) {
            marketData.putBackPurchaseOrder(order);
            sendMessage(seller, trc("purchase_order.cannot_complete", NamedTextColor.RED));
            return;
        }

        if (!economy.canDeposit(seller.getUniqueId(), total)) {
            marketData.putBackPurchaseOrder(order);
            sendMessage(seller, trc("error.balance_overflow", NamedTextColor.RED));
            return;
        }

        if (!removeMatchingItems(seller, order.stack, accepted)) {
            marketData.putBackPurchaseOrder(order);
            sendMessage(seller, trc("purchase_order.need_matching_items", NamedTextColor.RED, accepted));
            return;
        }

        EconomyData.MutationResult deposit = economy.tryDeposit(seller.getUniqueId(), total);
        if (deposit != EconomyData.MutationResult.SUCCESS) {
            // Restore the item and keep the reservation intact if a concurrent
            // mutation made the credit impossible.
            restoreMatchingItems(seller, order.stack, accepted);
            marketData.putBackPurchaseOrder(order);
            sendMessage(seller, trc("error.balance_overflow", NamedTextColor.RED));
            return;
        }
        marketData.addSale(total);

        ItemStack delivered = order.stack.clone();
        delivered.setAmount(accepted);
        marketDeliveries.add(order.buyer, delivered);

        Player buyerOnline = Bukkit.getPlayer(order.buyer);
        if (buyerOnline != null) {
            scheduler.runPlayer(buyerOnline, () -> {
                if (!buyerOnline.isOnline()) return;
                sendMessage(buyerOnline, trc("purchase_order.buyer_filled", NamedTextColor.GREEN,
                        MarketGui.getItemName(delivered), accepted, total));
                deliverQueuedItems(buyerOnline);
                if (marketDeliveries.has(order.buyer)) {
                    sendMessage(buyerOnline, trc("purchase_order.delivery_waiting", NamedTextColor.RED));
                }
            });
        }

        sendMessage(seller, trc("purchase_order.seller_filled", NamedTextColor.GREEN,
                MarketGui.getItemName(delivered), accepted, total));

        String buyerName = resolveMarketSellerName(order.buyer);
        transactionHistory.record(seller.getUniqueId(), "SELL_TO_ORDER", MarketGui.getItemName(delivered),
                accepted, total, buyerName);
        transactionHistory.record(order.buyer, "PURCHASE_FILL", MarketGui.getItemName(delivered),
                accepted, -total, seller.getName());
        marketData.save();
        economy.save();
        marketDeliveries.save();
        transactionHistory.save();
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
        // Persist the reduced queue immediately; otherwise a crash before the
        // periodic save can replay items that were already delivered.
        marketDeliveries.save();
        if (deliveredAmount > 0) {
            sendMessage(player, trc("market.delivery.received", NamedTextColor.YELLOW, deliveredAmount));
        }
    }

    private boolean removeMatchingItems(Player player, ItemStack requestedStack, int amount) {
        if (amount <= 0) {
            return false;
        }

        ItemStack probe = requestedStack.clone();
        probe.setAmount(1);
        int remaining = amount;
        int removedTotal = 0;
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
            removedTotal += removed;
        }
        if (remaining != 0 && removedTotal > 0) {
            restoreMatchingItems(player, requestedStack, removedTotal);
        }
        return remaining == 0;
    }

    private void restoreMatchingItems(Player player, ItemStack requestedStack, int amount) {
        if (amount <= 0 || requestedStack == null || Items.isEmpty(requestedStack)) {
            return;
        }
        ItemStack restore = requestedStack.clone();
        restore.setAmount(Math.min(amount, restore.getMaxStackSize()));
        int remaining = amount;
        while (remaining > 0) {
            int chunk = Math.min(remaining, restore.getMaxStackSize());
            restore.setAmount(chunk);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(restore.clone());
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            int left = 0;
            for (ItemStack item : leftover.values()) {
                left += item.getAmount();
            }
            remaining -= chunk - left;
            if (chunk - left <= 0) {
                break;
            }
        }
    }

    // ── Trade Confirmation Handlers ──

    public void handleSellConfirm(Player player, boolean confirmed, boolean promoted) {
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
        if (mustBlockMarketSale(player, inHand) || isCreativeOrigin(pending.stack)) {
            sendMessage(player, trc("error.creative_item_not_allowed", NamedTextColor.RED));
            return;
        }

        marketConfig.ensureLoaded();

        if (pending.price <= 0 || pending.price < marketConfig.getMinPrice()
                || pending.price > marketConfig.getMaxPrice()
                || Items.isEmpty(pending.stack) || pending.stack.getAmount() <= 0) {
            sendMessage(player, trc("error.invalid_price", NamedTextColor.RED));
            return;
        }

        // Repeat all mutable checks at confirmation time. The config or the
        // player's active listings may have changed while the GUI was open.
        int activeOffers = marketData.countOffersByOwner(player.getUniqueId());
        if (activeOffers >= marketConfig.getHardListingCap()) {
            sendMessage(player, trc("error.active_listing_limit", NamedTextColor.RED,
                    marketConfig.getHardListingCap()));
            return;
        }

        if (promoted && marketData.countPromotedBySeller(player.getUniqueId())
                >= marketConfig.getPromotedListingLimit()) {
            sendMessage(player, trc("error.promoted_listing_limit", NamedTextColor.RED,
                    marketConfig.getPromotedListingLimit()));
            return;
        }

        long promotionFee = promoted ? computePromotionFee(pending.price) : 0L;
        long totalFee;
        try {
            totalFee = Math.addExact(pending.fee, promotionFee);
        } catch (ArithmeticException e) {
            sendMessage(player, trc("error.invalid_price", NamedTextColor.RED));
            return;
        }

        // VaultEconomyProvider performs the balance check and withdrawal as one
        // operation. The fee is intentionally not refunded when a listing expires
        // or is cancelled.
        if (!withdrawMarketFee(player, totalFee)) {
            sendMessage(player, trc("error.not_enough_funds_fee", NamedTextColor.RED, totalFee));
            return;
        }

        // Remove item from hand only after the fee was successfully charged.
        // Keep a copy so an unexpected publication failure can restore it.
        ItemStack originalHand = inHand.clone();
        int removeCount = pending.stack.getAmount();
        if (inHand.getAmount() == removeCount) {
            player.getInventory().setItemInMainHand(Items.empty());
        } else {
            inHand.setAmount(inHand.getAmount() - removeCount);
        }

        final MarketData.Listing listing;
        try {
            listing = marketData.add(player.getUniqueId(), pending.stack.clone(), pending.price,
                    System.currentTimeMillis(), promoted);
        } catch (RuntimeException ex) {
            player.getInventory().setItemInMainHand(originalHand);
            // The fee is burned on successful listing only; return it if the
            // listing could not be created.
            if (vaultProvider != null) {
                vaultProvider.depositPlayer(player, totalFee);
            } else {
                economy.tryDeposit(player.getUniqueId(), totalFee);
            }
            getLogger().warning("Could not create listing: " + ex.getMessage());
            sendMessage(player, trc("error.transaction_failed", NamedTextColor.RED));
            return;
        }

        MarketNotifyData notifyState = marketNotify;
        long version = notifyState.bump();
        for (Player online : Bukkit.getOnlinePlayers()) {
            notifyState.markSeen(online.getUniqueId(), version);
        }

        sendMessage(player, trc("listing.created", NamedTextColor.GREEN, listing.id, pending.price, totalFee));
        if (promoted) {
            sendMessage(player, trc("listing.promoted", NamedTextColor.GOLD, promotionFee));
        }

        // Persist both sides immediately: promoted status and the burned fee.
        marketData.save();
        economy.save();
        refreshAllMarketGuis();

        if (pending.fee > 0) {
            transactionHistory.record(player.getUniqueId(), "FEE", MarketGui.getItemName(pending.stack),
                    pending.stack.getAmount(), -pending.fee, null);
        }
        if (promotionFee > 0) {
            transactionHistory.record(player.getUniqueId(), "PROMOTION_FEE", MarketGui.getItemName(pending.stack),
                    pending.stack.getAmount(), -promotionFee, null);
        }
        transactionHistory.save();
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
        if (mustBlockMarketSale(player, inHand) || isCreativeOrigin(pending.stack)) {
            sendMessage(player, trc("error.creative_item_not_allowed", NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "sellto", pending.itemId, pending.itemName,
                    pending.requested, inHand.getAmount(), 0, pending.price, 0L,
                    resource.limit(), 0,
                    economy.get(player.getUniqueId()), economy.get(player.getUniqueId()),
                    "REJECTED", "creative_origin");
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

        if (pending.price <= 0) {
            sendMessage(player, trc("error.invalid_price", NamedTextColor.RED));
            return;
        }
        final long total;
        try {
            total = Math.multiplyExact((long) accepted, pending.price);
        } catch (ArithmeticException e) {
            sendMessage(player, trc("error.invalid_price", NamedTextColor.RED));
            return;
        }
        long balanceBefore = economy.get(player.getUniqueId());

        if (!economy.canDeposit(player.getUniqueId(), total)) {
            sendMessage(player, trc("error.balance_overflow", NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "sellto", pending.itemId, pending.itemName,
                    pending.requested, available, 0, pending.price, 0L,
                    resource.limit(), remaining, balanceBefore, balanceBefore,
                    "REJECTED", "balance_overflow");
            return;
        }

        // Debit the actual item stack only after the credit has been proven
        // possible.  If the credit loses a race, restore the exact hand state.
        ItemStack originalHand = inHand.clone();
        if (inHand.getAmount() <= accepted) {
            player.getInventory().setItemInMainHand(Items.empty());
        } else {
            inHand.setAmount(inHand.getAmount() - accepted);
        }

        EconomyData.MutationResult deposit = economy.tryDeposit(player.getUniqueId(), total);
        if (deposit != EconomyData.MutationResult.SUCCESS) {
            player.getInventory().setItemInMainHand(originalHand);
            sendMessage(player, trc("error.balance_overflow", NamedTextColor.RED));
            return;
        }
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
        economy.save();
        bankState.save();
        transactionHistory.save();
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

        if (pending.price <= 0) {
            sendMessage(player, trc("error.invalid_price", NamedTextColor.RED));
            return;
        }

        if (resource.stock() <= 0) {
            sendMessage(player, trc("bank.out_of_stock", NamedTextColor.RED));
            MarketBankConfig.logTrade(getDataFolder().toPath(), player, "buyfrom", pending.itemId, pending.itemName,
                    pending.requested, 0, 0, pending.price, 0L,
                    buyLimit, remaining, economy.get(player.getUniqueId()), economy.get(player.getUniqueId()),
                    "REJECTED", "out_of_stock");
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

        int stockAvailable = (int) Math.min(Integer.MAX_VALUE, resource.stock());
        int accepted = Math.min(pending.accepted,
                Math.min(remaining, Math.min(Math.min(affordable, space), stockAvailable)));
        if (accepted <= 0) {
            sendMessage(player, trc("error.purchase_cannot_complete", NamedTextColor.RED));
            return;
        }

        final long total;
        try {
            total = Math.multiplyExact((long) accepted, pending.price);
        } catch (ArithmeticException e) {
            sendMessage(player, trc("error.invalid_price", NamedTextColor.RED));
            return;
        }
        long balanceBefore = economy.get(player.getUniqueId());
        EconomyData.MutationResult withdrawal = economy.tryWithdraw(player.getUniqueId(), total);
        if (withdrawal != EconomyData.MutationResult.SUCCESS) {
            sendMessage(player, trc("error.not_enough_funds", NamedTextColor.RED));
            return;
        }
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
        economy.save();
        bankState.save();
        transactionHistory.save();
    }

    public void handleBuyConfirm(Player player, boolean confirmed, int listingId) {
        if (!confirmed) return;

        Optional<MarketData.Listing> removedOpt = marketData.remove(listingId);
        if (removedOpt.isEmpty()) {
            sendMessage(player, trc("listing.already_sold", NamedTextColor.RED));
            return;
        }

        MarketData.Listing listing = removedOpt.get();
        if (listing.stack == null || Items.isEmpty(listing.stack) || listing.stack.getAmount() <= 0
                || listing.price <= 0) {
            marketData.putBack(listing);
            sendMessage(player, trc("error.transaction_failed", NamedTextColor.RED));
            return;
        }
        if (listing.seller.equals(player.getUniqueId())) {
            // Return item to seller
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(listing.stack.clone());
            if (!leftover.isEmpty()) {
                for (ItemStack item : leftover.values()) {
                    // Keep cancellation items in the durable delivery queue
                    // instead of dropping them into a potentially unsafe world.
                    marketDeliveries.add(player.getUniqueId(), item);
                }
            }
            sendMessage(player, trc("listing.removed_from_sale", NamedTextColor.GREEN));
            marketData.save();
            marketDeliveries.save();
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

        // Transfer funds atomically before giving the item.  This validates
        // both affordability and seller-side long overflow and prevents a
        // partial debit/credit settlement.
        EconomyData.MutationResult transfer = economy.tryTransfer(
                player.getUniqueId(), listing.seller, listing.price);
        if (transfer != EconomyData.MutationResult.SUCCESS) {
            marketData.putBack(listing);
            if (transfer == EconomyData.MutationResult.OVERFLOW) {
                sendMessage(player, trc("error.balance_overflow", NamedTextColor.RED));
            } else {
                sendMessage(player, trc("error.not_enough_funds", NamedTextColor.RED));
            }
            return;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(listing.stack.clone());
        if (!leftover.isEmpty()) {
            for (ItemStack item : leftover.values()) {
                marketDeliveries.add(player.getUniqueId(), item);
            }
        }

        marketData.addSale(listing.price);

        String itemName = MarketGui.getItemName(listing.stack);
        int count = listing.stack.getAmount();

        sendMessage(player, trc("listing.purchase_completed", NamedTextColor.GREEN, itemName, count, listing.price));

        Player sellerOnline = Bukkit.getPlayer(listing.seller);
        if (sellerOnline != null) {
            scheduler.runPlayer(sellerOnline, () -> {
                if (sellerOnline.isOnline()) {
                    sendMessage(sellerOnline, trc("listing.seller_notified", NamedTextColor.GREEN,
                            itemName, count, listing.price));
                }
            });
        }

        String sellerName = sellerOnline != null ? sellerOnline.getName() : listing.seller.toString();
        transactionHistory.record(player.getUniqueId(), "BUY", itemName, count, -listing.price, sellerName);
        transactionHistory.record(listing.seller, "SELL_INCOME", itemName, count, listing.price, player.getName());

        marketData.save();
        economy.save();
        marketDeliveries.save();
        transactionHistory.save();
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

    public boolean isBackKeyword(String input) {
        return matchesKeyword(input, BACK_KEYWORDS);
    }

    public void cancelPurchaseSetup(Player player) {
        UUID uuid = player.getUniqueId();
        pendingPurchaseItemSearchPrompts.remove(uuid);
        pendingPurchaseAmountPrompts.remove(uuid);
        pendingPurchaseTotalPricePrompts.remove(uuid);
        player.closeInventory();
        sendMessage(player, trc("purchase_setup.cancelled", NamedTextColor.YELLOW));
    }

    /** Command equivalent of typing "back" at the active purchase prompt. */
    public void goBackPurchaseSetup(Player player) {
        UUID uuid = player.getUniqueId();
        PurchaseTotalPricePrompt pricePrompt = pendingPurchaseTotalPricePrompts.remove(uuid);
        if (pricePrompt != null) {
            if (MarketData.getPurchaseOrderAmountLimit(new ItemStack(pricePrompt.material())) == 1) {
                openPurchaseSetup(player, pricePrompt.searchQuery());
            } else {
                promptPurchaseAmount(player, pricePrompt.material(), pricePrompt.searchQuery());
            }
            return;
        }
        PurchaseAmountPrompt amountPrompt = pendingPurchaseAmountPrompts.remove(uuid);
        if (amountPrompt != null) {
            openPurchaseSetup(player, amountPrompt.searchQuery());
            return;
        }
        PurchaseItemSearchPrompt searchPrompt = pendingPurchaseItemSearchPrompts.remove(uuid);
        if (searchPrompt != null || getOpenPurchaseSetupGui(player) != null) {
            promptPurchaseItemSearch(player);
            return;
        }
        sendMessage(player, trc("purchase_setup.nothing_to_go_back", NamedTextColor.YELLOW));
    }

    private int parsePurchaseAmount(String input, Material material) {
        int amount = Integer.parseInt(input);
        if (amount < 1 || amount > MarketData.getPurchaseOrderAmountLimit(new ItemStack(material))) {
            throw new NumberFormatException();
        }
        return amount;
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



