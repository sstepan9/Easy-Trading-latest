package com.easytrading.paper.listener;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.gui.ConfirmationGui;
import com.easytrading.paper.gui.MarketGui;
import com.easytrading.paper.gui.PurchaseSetupGui;
import com.easytrading.paper.trade.TradeManager;
import com.easytrading.paper.trade.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class PlayerListener implements Listener {
    private final EasyTradingPlugin plugin;

    public PlayerListener(EasyTradingPlugin plugin) {
        this.plugin = plugin;
    }

    /** Mark items spawned or moved by Creative inventory actions. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreativeInventoryClick(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || player.getGameMode() != GameMode.CREATIVE
                || !plugin.isCreativeItemBlockingEnabled()) return;
        event.setCursor(plugin.markCreativeOrigin(event.getCursor()));
        event.setCurrentItem(plugin.markCreativeOrigin(event.getCurrentItem()));
    }

    /**
     * Mark every carried item when leaving Creative. This closes routes such
     * as saved hotbars or container transfers that do not create a new item
     * through the Creative palette event.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeaveCreative(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE || event.getNewGameMode() == GameMode.CREATIVE
                || !plugin.isCreativeItemBlockingEnabled()) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = plugin.markCreativeOrigin(contents[slot]);
        }
        player.getInventory().setContents(contents);
        player.setItemOnCursor(plugin.markCreativeOrigin(player.getItemOnCursor()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Check if it's a trade GUI
        TradeManager tradeManager = plugin.getTradeManager();
        TradeSession session = tradeManager.getSession(player.getUniqueId());
        if (session != null && session.getGui() != null
                && session.getGui().getInventory() != null
                && event.getInventory().equals(session.getGui().getInventory())) {

            boolean isTopInventory = event.getRawSlot() < event.getInventory().getSize();
            boolean shouldCancel = session.handleClick(player, event.getRawSlot(),
                    event.getCursor(), event.getCurrentItem(), isTopInventory,
                    event.getAction(), event.getClick());

            if (shouldCancel) {
                event.setCancelled(true);
            } else if (!isTopInventory && event.isShiftClick()) {
                // Shift-click from player inventory — block it (prevent complexity)
                event.setCancelled(true);
            }
            return;
        }

        // Check if it's a confirmation GUI
        ConfirmationGui confirmGui = plugin.getOpenConfirmation(player);
        if (confirmGui != null && event.getInventory().equals(confirmGui.getInventory())) {
            event.setCancelled(true);
            confirmGui.handleClick(event.getRawSlot());
            return;
        }

        // Check if it's a market GUI
        MarketGui marketGui = plugin.getOpenMarketGui(player);
        if (marketGui != null && event.getInventory().equals(marketGui.getInventory())) {
            marketGui.handleClick(event);
            return;
        }

        PurchaseSetupGui purchaseSetupGui = plugin.getOpenPurchaseSetupGui(player);
        if (purchaseSetupGui != null && event.getInventory().equals(purchaseSetupGui.getInventory())) {
            purchaseSetupGui.handleClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Dragging into an escrow slot would be a multi-slot vanilla mutation
        // after this event. Block it instead of deferring a snapshot.
        TradeManager tradeManager = plugin.getTradeManager();
        TradeSession session = tradeManager.getSession(player.getUniqueId());
        if (session != null && session.getGui() != null
                && event.getInventory().equals(session.getGui().getInventory())) {
            int invSize = event.getInventory().getSize();
            for (int slot : event.getRawSlots()) {
                if (slot < invSize) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        // Trade GUI close — cancel trade (if not already finished)
        TradeManager tradeManager = plugin.getTradeManager();
        TradeSession session = tradeManager.getSession(player.getUniqueId());
        if (session != null && !session.isFinished()
                && session.getGui() != null
                && event.getInventory().equals(session.getGui().getInventory())) {
            // Delay to avoid issues with closeAll() during event
            plugin.getSchedulerAdapter().runPlayerLater(player, () -> {
                if (!session.isFinished()) {
                    session.cancelByPlayer(player.getUniqueId());
                }
            }, 1L);
            return;
        }

        MarketGui marketGui = plugin.getOpenMarketGui(player);
        if (marketGui != null && event.getInventory().equals(marketGui.getInventory())) {
            marketGui.handleClose();
            plugin.removeMarketGui(player);
        }

        PurchaseSetupGui purchaseSetupGui = plugin.getOpenPurchaseSetupGui(player);
        if (purchaseSetupGui != null && event.getInventory().equals(purchaseSetupGui.getInventory())) {
            purchaseSetupGui.handleClose();
        }

        ConfirmationGui confirmGui = plugin.getOpenConfirmation(player);
        if (confirmGui != null && event.getInventory().equals(confirmGui.getInventory())) {
            plugin.removeConfirmation(player);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        plugin.getTradeManager().handleDeath(player);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // Cancel trade if player changes world (e.g. teleport)
        plugin.getTradeManager().handleDeath(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.onPlayerLogin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.onPlayerLogout(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.hasPendingPurchaseItemSearchPrompt(player)) {
            event.setCancelled(true);
            String message = event.getMessage();
            plugin.getSchedulerAdapter().runPlayer(player, () -> plugin.handlePendingPurchaseItemSearchPrompt(player, message));
            return;
        }

        if (plugin.hasPendingPurchaseAmountPrompt(player)) {
            event.setCancelled(true);
            String message = event.getMessage();
            plugin.getSchedulerAdapter().runPlayer(player, () -> plugin.handlePendingPurchaseAmountPrompt(player, message));
            return;
        }

        if (plugin.hasPendingPurchaseTotalPricePrompt(player)) {
            event.setCancelled(true);
            String message = event.getMessage();
            plugin.getSchedulerAdapter().runPlayer(player, () -> plugin.handlePendingPurchasePricePrompt(player, message));
            return;
        }

        if (!plugin.hasPendingMarketPrompt(player)) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();
        plugin.getSchedulerAdapter().runPlayer(player, () -> plugin.handlePendingMarketPrompt(player, message));
    }
}

