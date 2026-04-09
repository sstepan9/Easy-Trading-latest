package com.easytrading.paper.gui;

import com.easytrading.paper.EasyTradingPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Confirmation GUI for sell, buy, and bank operations.
 * Uses a 3-row chest inventory with info item + confirm/cancel buttons.
 */
public class ConfirmationGui {
    public enum Type {
        SELL, BUY, BANK_SELL, BANK_BUY
    }

    private final EasyTradingPlugin plugin;
    private final Player player;
    private final Type type;
    private final int listingId; // for BUY
    private final String itemName;
    private final int count;
    private final long price;
    private final long fee; // for SELL
    private final String sellerName; // for BUY
    private final int taxPercent; // for BANK

    private Inventory inventory;

    public ConfirmationGui(EasyTradingPlugin plugin, Player player, Type type,
                           int listingId, String itemName, int count, long price, long fee,
                           String sellerName, int taxPercent) {
        this.plugin = plugin;
        this.player = player;
        this.type = type;
        this.listingId = listingId;
        this.itemName = itemName;
        this.count = count;
        this.price = price;
        this.fee = fee;
        this.sellerName = sellerName;
        this.taxPercent = taxPercent;
    }

    public void open() {
        String title = switch (type) {
            case SELL -> "Listing Confirmation";
            case BUY -> "Purchase Confirmation";
            case BANK_SELL -> "Sell to Bank";
            case BANK_BUY -> "Buy from Bank";
        };
        inventory = Bukkit.createInventory(null, 27,
                Component.text(title).color(NamedTextColor.DARK_GREEN).decoration(TextDecoration.BOLD, true));

        // Info item (slot 4 = center of row 1)
        ItemStack infoItem = new ItemStack(Material.BOOK, 1);
        ItemMeta infoMeta = infoItem.getItemMeta();
        List<Component> lore = new ArrayList<>();

        switch (type) {
            case SELL -> {
                infoMeta.displayName(Component.text("Listing Confirmation").color(NamedTextColor.GOLD));
                lore.add(Component.text("Item: " + itemName + " x" + count).color(NamedTextColor.WHITE));
                lore.add(Component.text("Listing price: " + price).color(NamedTextColor.GOLD));
                lore.add(Component.text("Fee: " + fee).color(NamedTextColor.RED));
                lore.add(Component.text("After confirmation, the item will be removed").color(NamedTextColor.GRAY));
                lore.add(Component.text("from your hand and listed on the market.").color(NamedTextColor.GRAY));
            }
            case BUY -> {
                infoMeta.displayName(Component.text("Purchase Confirmation").color(NamedTextColor.GOLD));
                lore.add(Component.text("Item: " + itemName + " x" + count).color(NamedTextColor.WHITE));
                lore.add(Component.text("Seller: " + sellerName).color(NamedTextColor.AQUA));
                lore.add(Component.text("Cost: " + price).color(NamedTextColor.GOLD));
                lore.add(Component.text("Press \"Confirm\" to complete the deal.").color(NamedTextColor.GRAY));
            }
            case BANK_SELL -> {
                infoMeta.displayName(Component.text("Sell to Bank").color(NamedTextColor.GOLD));
                lore.add(Component.text("Resource: " + itemName).color(NamedTextColor.WHITE));
                lore.add(Component.text("Requested: " + count).color(NamedTextColor.WHITE)); // using count for 'requested'
                lore.add(Component.text("Will be processed: " + listingId).color(NamedTextColor.WHITE)); // using listingId for 'accepted'
                lore.add(Component.text("Price per 1: " + price + " (tax " + taxPercent + "%)").color(NamedTextColor.GOLD));
                lore.add(Component.text("Total: " + fee).color(NamedTextColor.GOLD)); // using fee for 'total'
            }
            case BANK_BUY -> {
                infoMeta.displayName(Component.text("Buy from Bank").color(NamedTextColor.GOLD));
                lore.add(Component.text("Resource: " + itemName).color(NamedTextColor.WHITE));
                lore.add(Component.text("Requested: " + count).color(NamedTextColor.WHITE));
                lore.add(Component.text("Will be processed: " + listingId).color(NamedTextColor.WHITE));
                lore.add(Component.text("Price per 1: " + price + " (tax " + taxPercent + "%)").color(NamedTextColor.GOLD));
                lore.add(Component.text("Total: " + fee).color(NamedTextColor.GOLD));
            }
        }
        infoMeta.lore(lore);
        infoItem.setItemMeta(infoMeta);
        inventory.setItem(4, infoItem);

        // Confirm button (slot 11 = row 2, col 2)
        ItemStack confirmBtn = new ItemStack(Material.LIME_STAINED_GLASS_PANE, 1);
        ItemMeta confirmMeta = confirmBtn.getItemMeta();
        confirmMeta.displayName(Component.text("Confirm").color(NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
        confirmBtn.setItemMeta(confirmMeta);
        inventory.setItem(11, confirmBtn);

        // Cancel button (slot 15 = row 2, col 6)
        ItemStack cancelBtn = new ItemStack(Material.RED_STAINED_GLASS_PANE, 1);
        ItemMeta cancelMeta = cancelBtn.getItemMeta();
        cancelMeta.displayName(Component.text("Cancel").color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        cancelBtn.setItemMeta(cancelMeta);
        inventory.setItem(15, cancelBtn);

        plugin.registerConfirmation(player, this);
        player.openInventory(inventory);
    }

    public void handleClick(int rawSlot) {
        if (rawSlot == 11) {
            // Confirm
            player.closeInventory();
            switch (type) {
                case SELL -> plugin.handleSellConfirm(player, true);
                case BUY -> plugin.handleBuyConfirm(player, true, listingId);
                case BANK_SELL -> plugin.handleBankConfirm(player, true);
                case BANK_BUY -> plugin.handleBankConfirm(player, true);
            }
        } else if (rawSlot == 15) {
            // Cancel
            player.closeInventory();
            switch (type) {
                case SELL -> plugin.handleSellConfirm(player, false);
                case BUY -> plugin.handleBuyConfirm(player, false, listingId);
                case BANK_SELL -> plugin.handleBankConfirm(player, false);
                case BANK_BUY -> plugin.handleBankConfirm(player, false);
            }
        }
    }

    public Inventory getInventory() { return inventory; }
    public Player getPlayer() { return player; }
    public Type getType() { return type; }
}
