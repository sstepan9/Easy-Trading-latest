package com.easytrading.paper.listener;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.gui.ConfirmationGui;
import com.easytrading.paper.gui.MarketGui;
import com.easytrading.paper.trade.TradeGui;
import com.easytrading.paper.trade.TradeManager;
import com.easytrading.paper.trade.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {
    private final EasyTradingPlugin plugin;

    public PlayerListener(EasyTradingPlugin plugin) {
        this.plugin = plugin;
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
                    event.getCursor(), event.getCurrentItem(), isTopInventory);

            if (shouldCancel) {
                event.setCancelled(true);
            } else if (isTopInventory && session.isPlayerSlot(player.getUniqueId(), event.getRawSlot())) {
                // Player interacting with own trade slot — sync items after tick
                Bukkit.getScheduler().runTask(plugin, session::syncItems);
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
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Block dragging in trade GUI
        TradeManager tradeManager = plugin.getTradeManager();
        TradeSession session = tradeManager.getSession(player.getUniqueId());
        if (session != null && session.getGui() != null
                && event.getInventory().equals(session.getGui().getInventory())) {
            // Only allow if all dragged slots are in the player's own offer area
            int invSize = event.getInventory().getSize();
            for (int slot : event.getRawSlots()) {
                if (slot < invSize) {
                    if (!session.isPlayerSlot(player.getUniqueId(), slot)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            // Sync after tick if drag was in trade slots
            Bukkit.getScheduler().runTask(plugin, session::syncItems);
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
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!session.isFinished()) {
                    session.cancelByPlayer(player.getUniqueId());
                }
            });
            return;
        }

        MarketGui marketGui = plugin.getOpenMarketGui(player);
        if (marketGui != null && event.getInventory().equals(marketGui.getInventory())) {
            marketGui.handleClose();
            plugin.removeMarketGui(player);
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
}
