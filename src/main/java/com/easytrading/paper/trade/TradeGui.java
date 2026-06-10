package com.easytrading.paper.trade;

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
import java.util.UUID;

/**
 * The visual trade GUI — a 6-row chest shared by both players.
 * Each player sees the same inventory but can only interact with their own side.
 */
public class TradeGui {

    private final TradeSession session;
    private final UUID player1;
    private final UUID player2;
    private Inventory inventory;

    // Keep player references for opening
    private final Player p1;
    private final Player p2;

    public TradeGui(TradeSession session, Player p1, Player p2) {
        this.session = session;
        this.player1 = p1.getUniqueId();
        this.player2 = p2.getUniqueId();
        this.p1 = p1;
        this.p2 = p2;
    }

    public void open() {
        String title = session.getPlugin().tr("trade.gui.title", p1.getName(), p2.getName());
        inventory = Bukkit.createInventory(null, 54,
                AdventureSupport.legacy(Component.text(title)
                        .color(NamedTextColor.DARK_GREEN)
                        .decoration(TextDecoration.BOLD, true)));
        refresh();
        p1.openInventory(inventory);
        p2.openInventory(inventory);
    }

    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Refresh decorations (separators, money info, confirm buttons, status).
     * Does NOT touch item offer slots — those are managed directly by inventory clicks.
     */
    public void refresh() {
        if (inventory == null) return;

        // Separators
        ItemStack separator = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", null);
        for (int slot : TradeSession.SEPARATOR_SLOTS) {
            inventory.setItem(slot, separator);
        }

        // Fill row 4 non-functional slots with separator
        inventory.setItem(39, separator);
        inventory.setItem(44, separator);

        // Player1 money info
        inventory.setItem(TradeSession.P1_MONEY_INFO, createMoneyInfo(player1));
        inventory.setItem(TradeSession.P1_MONEY_ADD, createMoneyButton(true, player1));
        inventory.setItem(TradeSession.P1_MONEY_SUB, createMoneyButton(false, player1));

        // Player2 money info
        inventory.setItem(TradeSession.P2_MONEY_INFO, createMoneyInfo(player2));
        inventory.setItem(TradeSession.P2_MONEY_ADD, createMoneyButton(true, player2));
        inventory.setItem(TradeSession.P2_MONEY_SUB, createMoneyButton(false, player2));

        // Confirm/Cancel buttons for P1
        inventory.setItem(TradeSession.P1_CONFIRM, createConfirmButton(player1));
        inventory.setItem(TradeSession.P1_CANCEL, createCancelButton());

        // Confirm/Cancel buttons for P2
        inventory.setItem(TradeSession.P2_CONFIRM, createConfirmButton(player2));
        inventory.setItem(TradeSession.P2_CANCEL, createCancelButton());

        // Status
        inventory.setItem(TradeSession.STATUS_SLOT, createStatusItem());

        // Row 5 filler (slots 46, 48, 50)
        inventory.setItem(46, separator);
        inventory.setItem(48, separator);
        inventory.setItem(50, separator);
    }

    /**
     * Update just the status item (for countdown).
     */
    public void updateStatus(String text) {
        if (inventory == null) return;
        ItemStack status = createItem(Material.CLOCK, text, List.of(
                Component.text(session.getPlugin().tr("trade.gui.status.both_confirmed")).color(NamedTextColor.GRAY)
        ));
        inventory.setItem(TradeSession.STATUS_SLOT, status);
    }

    public void closeAll() {
        if (p1 != null && p1.isOnline()) {
            if (p1.getOpenInventory().getTopInventory().equals(inventory)) {
                p1.closeInventory();
            }
        }
        if (p2 != null && p2.isOnline()) {
            if (p2.getOpenInventory().getTopInventory().equals(inventory)) {
                p2.closeInventory();
            }
        }
    }

    // ── Item Creation Helpers ──

    private ItemStack createMoneyInfo(UUID playerUuid) {
        long offered = session.getMoney(playerUuid);
        String playerName = playerUuid.equals(player1) ? p1.getName() : p2.getName();
        ItemStack item = new ItemStack(Material.GOLD_NUGGET, Math.max(1, (int) Math.min(64, offered)));
        ItemMeta meta = item.getItemMeta();
        AdventureSupport.displayName(meta, Component.text(session.getPlugin().tr("trade.gui.money_offer", playerName)).color(NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(session.getPlugin().tr("trade.gui.money_offered", offered)).color(NamedTextColor.YELLOW));
        AdventureSupport.lore(meta, lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createMoneyButton(boolean add, UUID playerUuid) {
        Material mat = add ? Material.LIME_DYE : Material.RED_DYE;
        String label = add ? "+10" : "-10";
        NamedTextColor color = add ? NamedTextColor.GREEN : NamedTextColor.RED;
        return createItem(mat, label, List.of(
                Component.text(add ? session.getPlugin().tr("trade.gui.money_add") : session.getPlugin().tr("trade.gui.money_remove"))
                        .color(NamedTextColor.GRAY)
        ), color);
    }

    private ItemStack createConfirmButton(UUID playerUuid) {
        boolean confirmed = session.isConfirmed(playerUuid);
        Material mat = confirmed ? Material.LIME_STAINED_GLASS_PANE : Material.GREEN_STAINED_GLASS_PANE;
        String label = confirmed ? session.getPlugin().tr("trade.gui.confirmed") : session.getPlugin().tr("gui.common.confirm");
        NamedTextColor color = confirmed ? NamedTextColor.GREEN : NamedTextColor.DARK_GREEN;
        List<Component> lore = new ArrayList<>();
        if (confirmed) {
            lore.add(Component.text(session.getPlugin().tr("trade.gui.unconfirm")).color(NamedTextColor.GRAY));
        } else {
            lore.add(Component.text(session.getPlugin().tr("trade.gui.confirm_hint")).color(NamedTextColor.GRAY));
        }
        return createItem(mat, label, lore, color);
    }

    private ItemStack createCancelButton() {
        return createItem(Material.RED_STAINED_GLASS_PANE, session.getPlugin().tr("trade.gui.cancel_trade"), List.of(
                Component.text(session.getPlugin().tr("trade.gui.cancel_hint")).color(NamedTextColor.GRAY)
        ), NamedTextColor.RED);
    }

    private ItemStack createStatusItem() {
        boolean p1c = session.isConfirmed(player1);
        boolean p2c = session.isConfirmed(player2);

        String statusText;
        Material mat;
        if (p1c && p2c) {
            statusText = session.getPlugin().tr("trade.gui.status.both_confirmed_short");
            mat = Material.LIME_WOOL;
        } else if (p1c || p2c) {
            String who = p1c ? p1.getName() : p2.getName();
            statusText = session.getPlugin().tr("trade.gui.status.one_confirmed", who);
            mat = Material.YELLOW_WOOL;
        } else {
            statusText = session.getPlugin().tr("trade.gui.status.waiting");
            mat = Material.RED_WOOL;
        }

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(session.getPlugin().tr("trade.gui.status.player_state", p1.getName(), p1c ? "✔" : "✘"))
                .color(p1c ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(Component.text(session.getPlugin().tr("trade.gui.status.player_state", p2.getName(), p2c ? "✔" : "✘"))
                .color(p2c ? NamedTextColor.GREEN : NamedTextColor.RED));

        return createItem(mat, statusText, lore);
    }

    private ItemStack createItem(Material material, String name, List<Component> lore) {
        return createItem(material, name, lore, NamedTextColor.WHITE);
    }

    private ItemStack createItem(Material material, String name, List<Component> lore, NamedTextColor color) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        AdventureSupport.displayName(meta, Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        if (lore != null) AdventureSupport.lore(meta, lore);
        item.setItemMeta(meta);
        return item;
    }
}

