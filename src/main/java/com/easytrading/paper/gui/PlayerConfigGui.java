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

/** Per-player display settings available through /market config. */
public final class PlayerConfigGui {
    private static final int SIZE = 27;
    private static final int BALANCE_TAB_SLOT = 13;
    private static final int TOP_LINE_SLOT = 11;
    private static final int TAB_LIST_SLOT = 15;
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

    public Inventory getInventory() {
        return inventory;
    }

    public void handleClose() {
        plugin.removePlayerConfigGui(player);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (page == Page.HOME && slot == BALANCE_TAB_SLOT) {
            page = Page.BALANCE;
            refresh();
            return;
        }
        if (page == Page.BALANCE) {
            if (slot == TOP_LINE_SLOT) {
                plugin.setPlayerHudMode(player, EasyTradingPlugin.HudMode.BOSS_BAR);
                refresh();
            } else if (slot == TAB_LIST_SLOT) {
                plugin.setPlayerHudMode(player, EasyTradingPlugin.HudMode.TAB_FOOTER);
                refresh();
            } else if (slot == BACK_SLOT) {
                page = Page.HOME;
                refresh();
            }
        }
    }

    private Component title() {
        String key = page == Page.HOME ? "gui.config.title" : "gui.config.balance.title";
        return Component.text(plugin.tr(key)).color(NamedTextColor.DARK_GREEN).decoration(TextDecoration.BOLD, true);
    }

    private void refresh() {
        inventory.clear();
        if (page == Page.HOME) {
            inventory.setItem(BALANCE_TAB_SLOT, button(Material.GOLD_INGOT, "gui.config.balance.name",
                    NamedTextColor.GOLD, List.of(plugin.tr("gui.config.balance.desc"))));
            return;
        }

        EasyTradingPlugin.HudMode selected = plugin.getPlayerHudMode(player);
        inventory.setItem(TOP_LINE_SLOT, button(Material.BEACON, "gui.config.balance.top_line",
                selected == EasyTradingPlugin.HudMode.BOSS_BAR ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                choiceLore("gui.config.balance.top_line.desc", selected == EasyTradingPlugin.HudMode.BOSS_BAR)));
        inventory.setItem(TAB_LIST_SLOT, button(Material.PLAYER_HEAD, "gui.config.balance.tab_list",
                selected == EasyTradingPlugin.HudMode.TAB_FOOTER ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                choiceLore("gui.config.balance.tab_list.desc", selected == EasyTradingPlugin.HudMode.TAB_FOOTER)));
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "gui.common.back", NamedTextColor.YELLOW, List.of()));
    }

    private List<String> choiceLore(String descriptionKey, boolean selected) {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.tr(descriptionKey));
        lore.add(plugin.tr(selected ? "gui.config.selected" : "gui.config.select"));
        return lore;
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

    private enum Page { HOME, BALANCE }
}
