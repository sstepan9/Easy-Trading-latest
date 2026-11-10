package com.easytrading.paper.gui;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.util.AdventureSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Per-player display and direct-trade settings available through /market config. */
public final class PlayerConfigGui {
    private static final int SIZE = 27;
    private static final int BALANCE_TAB_SLOT = 11;
    private static final int TRADING_TAB_SLOT = 15;
    private static final int TOP_LINE_SLOT = 10;
    private static final int TAB_LIST_SLOT = 12;
    private static final int NUMBER_FORMAT_SLOT = 14;
    private static final int GROUPED_SLOT = 11;
    private static final int COMPACT_SLOT = 15;
    private static final int REQUEST_ALL_SLOT = 10;
    private static final int REQUEST_50_SLOT = 11;
    private static final int REQUEST_100_SLOT = 12;
    private static final int REQUEST_150_SLOT = 13;
    private static final int REQUEST_NONE_SLOT = 14;
    private static final int BACK_SLOT = 18;

    private final EasyTradingPlugin plugin;
    private final Player player;
    private Page page = Page.HOME;
    private Inventory inventory;

    public PlayerConfigGui(EasyTradingPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        inventory = Bukkit.createInventory(null, SIZE, AdventureSupport.legacy(title()));
        refresh();
        player.openInventory(inventory);
    }

    public Inventory getInventory() { return inventory; }
    public void handleClose() { plugin.removePlayerConfigGui(player); }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        switch (page) {
            case HOME -> handleHomeClick(slot);
            case BALANCE -> handleBalanceClick(slot);
            case NUMBER_FORMAT -> handleNumberFormatClick(slot);
            case TRADING -> handleTradingClick(slot);
        }
    }

    private void handleHomeClick(int slot) {
        if (slot == BALANCE_TAB_SLOT) page = Page.BALANCE;
        else if (slot == TRADING_TAB_SLOT) page = Page.TRADING;
        else return;
        refresh();
    }

    private void handleBalanceClick(int slot) {
        if (slot == TOP_LINE_SLOT) plugin.setPlayerHudMode(player, EasyTradingPlugin.HudMode.BOSS_BAR);
        else if (slot == TAB_LIST_SLOT) plugin.setPlayerHudMode(player, EasyTradingPlugin.HudMode.TAB_FOOTER);
        else if (slot == NUMBER_FORMAT_SLOT) { page = Page.NUMBER_FORMAT; refresh(); return; }
        else if (slot == BACK_SLOT) { page = Page.HOME; refresh(); return; }
        else return;
        refresh();
    }

    private void handleNumberFormatClick(int slot) {
        if (slot == GROUPED_SLOT) plugin.setPlayerNumberFormat(player, EasyTradingPlugin.NumberFormatMode.GROUPED);
        else if (slot == COMPACT_SLOT) plugin.setPlayerNumberFormat(player, EasyTradingPlugin.NumberFormatMode.COMPACT);
        else if (slot == BACK_SLOT) { page = Page.BALANCE; refresh(); return; }
        else return;
        refresh();
    }

    private void handleTradingClick(int slot) {
        EasyTradingPlugin.TradeRequestMode mode = switch (slot) {
            case REQUEST_ALL_SLOT -> EasyTradingPlugin.TradeRequestMode.ALL;
            case REQUEST_50_SLOT -> EasyTradingPlugin.TradeRequestMode.RADIUS_50;
            case REQUEST_100_SLOT -> EasyTradingPlugin.TradeRequestMode.RADIUS_100;
            case REQUEST_150_SLOT -> EasyTradingPlugin.TradeRequestMode.RADIUS_150;
            case REQUEST_NONE_SLOT -> EasyTradingPlugin.TradeRequestMode.NONE;
            default -> null;
        };
        if (mode != null) {
            plugin.setTradeRequestMode(player, mode);
            refresh();
        } else if (slot == BACK_SLOT) {
            page = Page.HOME;
            refresh();
        }
    }

    private Component title() {
        String key = switch (page) {
            case HOME -> "gui.config.title";
            case BALANCE -> "gui.config.balance.title";
            case NUMBER_FORMAT -> "gui.config.number_format.title";
            case TRADING -> "gui.config.trading.title";
        };
        return Component.text(plugin.tr(key)).color(NamedTextColor.DARK_GREEN).decoration(TextDecoration.BOLD, true);
    }

    private void refresh() {
        inventory.clear();
        switch (page) {
            case HOME -> renderHome();
            case BALANCE -> renderBalance();
            case NUMBER_FORMAT -> renderNumberFormat();
            case TRADING -> renderTrading();
        }
    }

    private void renderHome() {
        inventory.setItem(BALANCE_TAB_SLOT, button(Material.GOLD_INGOT, "gui.config.balance.name", NamedTextColor.GOLD,
                List.of(plugin.tr("gui.config.balance.desc"))));
        inventory.setItem(TRADING_TAB_SLOT, button(Material.EMERALD, "gui.config.trading.name", NamedTextColor.GREEN,
                List.of(plugin.tr("gui.config.trading.desc"))));
    }

    private void renderBalance() {
        EasyTradingPlugin.HudMode display = plugin.getPlayerHudMode(player);
        inventory.setItem(TOP_LINE_SLOT, choice(Material.BEACON, "gui.config.balance.top_line",
                "gui.config.balance.top_line.desc", display == EasyTradingPlugin.HudMode.BOSS_BAR));
        inventory.setItem(TAB_LIST_SLOT, choice(Material.PLAYER_HEAD, "gui.config.balance.tab_list",
                "gui.config.balance.tab_list.desc", display == EasyTradingPlugin.HudMode.TAB_FOOTER));
        inventory.setItem(NUMBER_FORMAT_SLOT, button(Material.PAPER, "gui.config.number_format.name", NamedTextColor.GOLD,
                List.of(plugin.tr("gui.config.number_format.desc", plugin.getPlayerNumberFormat(player).example()))));
        back();
    }

    private void renderNumberFormat() {
        EasyTradingPlugin.NumberFormatMode selected = plugin.getPlayerNumberFormat(player);
        inventory.setItem(GROUPED_SLOT, choice(Material.GOLD_NUGGET, "gui.config.number_format.grouped",
                "gui.config.number_format.grouped.desc", selected == EasyTradingPlugin.NumberFormatMode.GROUPED));
        inventory.setItem(COMPACT_SLOT, choice(Material.NETHER_STAR, "gui.config.number_format.compact",
                "gui.config.number_format.compact.desc", selected == EasyTradingPlugin.NumberFormatMode.COMPACT));
        back();
    }

    private void renderTrading() {
        EasyTradingPlugin.TradeRequestMode selected = plugin.getTradeRequestMode(player);
        inventory.setItem(REQUEST_ALL_SLOT, tradeChoice(Material.LIME_DYE, "gui.config.trading.requests.all", selected,
                EasyTradingPlugin.TradeRequestMode.ALL));
        inventory.setItem(REQUEST_50_SLOT, tradeChoice(Material.YELLOW_DYE, "gui.config.trading.requests.radius_50", selected,
                EasyTradingPlugin.TradeRequestMode.RADIUS_50));
        inventory.setItem(REQUEST_100_SLOT, tradeChoice(Material.ORANGE_DYE, "gui.config.trading.requests.radius_100", selected,
                EasyTradingPlugin.TradeRequestMode.RADIUS_100));
        inventory.setItem(REQUEST_150_SLOT, tradeChoice(Material.RED_DYE, "gui.config.trading.requests.radius_150", selected,
                EasyTradingPlugin.TradeRequestMode.RADIUS_150));
        inventory.setItem(REQUEST_NONE_SLOT, tradeChoice(Material.BARRIER, "gui.config.trading.requests.none", selected,
                EasyTradingPlugin.TradeRequestMode.NONE));
        back();
    }

    private ItemStack tradeChoice(Material material, String title, EasyTradingPlugin.TradeRequestMode selected,
                                  EasyTradingPlugin.TradeRequestMode choice) {
        return choice(material, title, "gui.config.trading.requests.desc", selected == choice);
    }

    private ItemStack choice(Material material, String title, String description, boolean selected) {
        return button(material, title, selected ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                List.of(plugin.tr(description), plugin.tr(selected ? "gui.config.selected" : "gui.config.select")));
    }

    private void back() {
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "gui.common.back", NamedTextColor.YELLOW, List.of()));
    }

    private ItemStack button(Material material, String titleKey, NamedTextColor color, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        AdventureSupport.displayName(meta, Component.text(plugin.tr(titleKey)).color(color));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) lore.add(Component.text(line).color(NamedTextColor.GRAY));
        AdventureSupport.lore(meta, lore);
        item.setItemMeta(meta);
        return item;
    }

    private enum Page { HOME, BALANCE, NUMBER_FORMAT, TRADING }
}
