package com.easytrading.paper.gui;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.util.AdventureSupport;
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
        SELL, BUY, BANK_SELL, BANK_BUY, PURCHASE_CREATE, SELL_TO_ORDER
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
            case SELL -> plugin.tr("gui.confirm.title.sell");
            case BUY -> plugin.tr("gui.confirm.title.buy");
            case BANK_SELL -> plugin.tr("gui.confirm.title.bank_sell");
            case BANK_BUY -> plugin.tr("gui.confirm.title.bank_buy");
            case PURCHASE_CREATE -> plugin.tr("gui.confirm.title.purchase_create");
            case SELL_TO_ORDER -> plugin.tr("gui.confirm.title.sell_to_order");
        };
        inventory = Bukkit.createInventory(null, 27,
                AdventureSupport.legacy(Component.text(title).color(NamedTextColor.DARK_GREEN).decoration(TextDecoration.BOLD, true)));

        // Info item (slot 4 = center of row 1)
        ItemStack infoItem = new ItemStack(Material.BOOK, 1);
        ItemMeta infoMeta = infoItem.getItemMeta();
        List<Component> lore = new ArrayList<>();

        switch (type) {
            case SELL -> {
                AdventureSupport.displayName(infoMeta, Component.text(plugin.tr("gui.confirm.sell.name")).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.item_line", itemName, count)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.sell.price", price)).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.sell.fee", fee)).color(NamedTextColor.RED));
                lore.add(Component.text(plugin.tr("gui.confirm.sell.line1")).color(NamedTextColor.GRAY));
                lore.add(Component.text(plugin.tr("gui.confirm.sell.line2")).color(NamedTextColor.GRAY));
            }
            case BUY -> {
                AdventureSupport.displayName(infoMeta, Component.text(plugin.tr("gui.confirm.buy.name")).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.item_line", itemName, count)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.buy.seller", sellerName)).color(NamedTextColor.AQUA));
                lore.add(Component.text(plugin.tr("gui.confirm.buy.cost", price)).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.buy.line1")).color(NamedTextColor.GRAY));
            }
            case BANK_SELL -> {
                AdventureSupport.displayName(infoMeta, Component.text(plugin.tr("gui.confirm.bank_sell.name")).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.resource", itemName)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.requested", count)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.accepted", listingId)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.price_tax", price, taxPercent)).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.total", fee)).color(NamedTextColor.GOLD));
            }
            case BANK_BUY -> {
                AdventureSupport.displayName(infoMeta, Component.text(plugin.tr("gui.confirm.bank_buy.name")).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.resource", itemName)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.requested", count)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.accepted", listingId)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.price_tax", price, taxPercent)).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.bank.total", fee)).color(NamedTextColor.GOLD));
            }
            case PURCHASE_CREATE -> {
                AdventureSupport.displayName(infoMeta, Component.text(plugin.tr("gui.confirm.purchase_create.name")).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.item_line", itemName, count)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.purchase_create.total", price)).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.purchase_create.line1")).color(NamedTextColor.GRAY));
                lore.add(Component.text(plugin.tr("gui.confirm.purchase_create.line2")).color(NamedTextColor.GRAY));
            }
            case SELL_TO_ORDER -> {
                AdventureSupport.displayName(infoMeta, Component.text(plugin.tr("gui.confirm.sell_to_order.name")).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.sell_to_order.buyer", sellerName)).color(NamedTextColor.AQUA));
                lore.add(Component.text(plugin.tr("gui.confirm.item_line", itemName, count)).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.confirm.sell_to_order.total", fee)).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.sell_to_order.receive", fee)).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.confirm.sell_to_order.line1")).color(NamedTextColor.GRAY));
                lore.add(Component.text(plugin.tr("gui.confirm.sell_to_order.line2")).color(NamedTextColor.GRAY));
            }
        }
        AdventureSupport.lore(infoMeta, lore);
        infoItem.setItemMeta(infoMeta);
        inventory.setItem(4, infoItem);

        // Confirm button (slot 11 = row 2, col 2)
        ItemStack confirmBtn = new ItemStack(Material.LIME_STAINED_GLASS_PANE, 1);
        ItemMeta confirmMeta = confirmBtn.getItemMeta();
        AdventureSupport.displayName(confirmMeta, Component.text(plugin.tr("gui.common.confirm")).color(NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
        confirmBtn.setItemMeta(confirmMeta);
        inventory.setItem(11, confirmBtn);

        // Cancel button (slot 15 = row 2, col 6)
        ItemStack cancelBtn = new ItemStack(Material.RED_STAINED_GLASS_PANE, 1);
        ItemMeta cancelMeta = cancelBtn.getItemMeta();
        AdventureSupport.displayName(cancelMeta, Component.text(plugin.tr("gui.common.cancel")).color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
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
                case PURCHASE_CREATE -> plugin.handlePurchaseOrderCreateConfirm(player, true);
                case SELL_TO_ORDER -> plugin.handlePurchaseOrderSaleConfirm(player, true, listingId);
            }
        } else if (rawSlot == 15) {
            // Cancel
            player.closeInventory();
            switch (type) {
                case SELL -> plugin.handleSellConfirm(player, false);
                case BUY -> plugin.handleBuyConfirm(player, false, listingId);
                case BANK_SELL -> plugin.handleBankConfirm(player, false);
                case BANK_BUY -> plugin.handleBankConfirm(player, false);
                case PURCHASE_CREATE -> plugin.handlePurchaseOrderCreateConfirm(player, false);
                case SELL_TO_ORDER -> plugin.handlePurchaseOrderSaleConfirm(player, false, listingId);
            }
        }
    }

    public Inventory getInventory() { return inventory; }
    public Player getPlayer() { return player; }
    public Type getType() { return type; }
}

