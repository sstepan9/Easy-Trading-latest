package com.easytrading.paper.listener;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.gui.ConfirmationGui;
import com.easytrading.paper.gui.MarketGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
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

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

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
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.onPlayerLogin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.onPlayerLogout(event.getPlayer());
    }
}
