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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class PurchaseSetupGui {
    public enum Stage {
        BLOCK,
        AMOUNT
    }

    private static final int TOTAL_SLOTS = 54;
    private static final int ITEMS_PER_PAGE = 45;

    private static final int PREV_SLOT = 45;
    private static final int BACK_SLOT = 46;
    private static final int AMOUNT_DOWN_SLOT = 47;
    private static final int SELECTED_SLOT = 48;
    private static final int AMOUNT_UP_SLOT = 49;
    private static final int SEARCH_SLOT = 50;
    private static final int ACTION_SLOT = 51;
    private static final int CANCEL_SLOT = 52;
    private static final int NEXT_SLOT = 53;

    private static final List<Material> BLOCKS = Arrays.stream(Material.values())
            .filter(Material::isBlock)
            .filter(Material::isItem)
            .filter(material -> !material.isAir())
            .filter(PurchaseSetupGui::canCreateItemStack)
            .sorted(Comparator.comparing(Enum::name))
            .toList();

    private final EasyTradingPlugin plugin;
    private final Player viewer;
    private final Stage stage;
    private final String searchQuery;
    private Material selectedMaterial;
    private int amount;
    private int currentPage;
    private Inventory inventory;

    public PurchaseSetupGui(EasyTradingPlugin plugin, Player viewer, Stage stage,
                            Material selectedMaterial, int amount, String searchQuery) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.stage = stage == null ? Stage.BLOCK : stage;
        this.searchQuery = normalize(searchQuery);
        this.selectedMaterial = isSelectableBlock(selectedMaterial) ? selectedMaterial : Material.STONE;
        this.amount = Math.max(1, amount);
        this.currentPage = findPageFor(this.selectedMaterial, this.searchQuery);
    }

    public void open() {
        String title = stage == Stage.BLOCK
                ? plugin.tr("gui.purchase.title.block")
                : plugin.tr("gui.purchase.title.amount");
        inventory = Bukkit.createInventory(null, TOTAL_SLOTS,
                AdventureSupport.legacy(Component.text(title).color(NamedTextColor.DARK_GREEN)
                        .decoration(TextDecoration.BOLD, true)));
        refresh();
        viewer.openInventory(inventory);
    }

    public Inventory getInventory() {
        return inventory;
    }

    public void refresh() {
        inventory.clear();
        if (stage == Stage.BLOCK) {
            refreshBlockStage();
        } else {
            refreshAmountStage();
        }
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= TOTAL_SLOTS) {
            return;
        }

        if (stage == Stage.BLOCK) {
            handleBlockStageClick(rawSlot);
        } else {
            handleAmountStageClick(event, rawSlot);
        }
    }

    public void handleClose() {
        plugin.removePurchaseSetupGui(viewer);
    }

    private void refreshBlockStage() {
        List<Material> filteredBlocks = getFilteredBlocks();
        int totalPages = Math.max(1, (filteredBlocks.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        if (currentPage < 0) currentPage = 0;
        if (currentPage >= totalPages) currentPage = totalPages - 1;

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filteredBlocks.size());
        for (int slot = 0, i = startIndex; i < endIndex; i++, slot++) {
            Material material = filteredBlocks.get(i);
            ItemStack item = createSafeItemStack(material, 1);
            if (item == null) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(plugin.tr("gui.purchase.block.select_hint")).color(NamedTextColor.GRAY));
                if (material == selectedMaterial) {
                    lore.add(Component.text(plugin.tr("gui.purchase.block.selected")).color(NamedTextColor.GREEN));
                }
                AdventureSupport.lore(meta, lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
        }

        if (currentPage > 0) {
            inventory.setItem(PREV_SLOT, createButton(Material.ARROW, plugin.tr("gui.common.back"),
                    plugin.tr("gui.common.page.previous")));
        }
        if (currentPage < totalPages - 1) {
            inventory.setItem(NEXT_SLOT, createButton(Material.ARROW, plugin.tr("gui.common.forward"),
                    plugin.tr("gui.common.page.next")));
        }

        inventory.setItem(BACK_SLOT, createButton(Material.COMPASS, plugin.tr("gui.purchase.step1.title"),
                plugin.tr("gui.purchase.step1.desc")));
        inventory.setItem(SELECTED_SLOT, buildSelectedBlockItem());

        String searchLabel = searchQuery == null
                ? plugin.tr("gui.purchase.search.label")
                : plugin.tr("gui.purchase.search.active", searchQuery);
        List<String> searchLore = new ArrayList<>();
        searchLore.add(plugin.tr("gui.purchase.search.hint"));
        if (searchQuery != null) {
            searchLore.add(plugin.tr("gui.purchase.search.current", searchQuery));
            searchLore.add(plugin.tr("gui.purchase.search.reset"));
        }
        inventory.setItem(SEARCH_SLOT, createButton(searchQuery == null ? Material.NAME_TAG : Material.WRITABLE_BOOK,
                searchLabel, searchLore.toArray(String[]::new)));

        inventory.setItem(ACTION_SLOT, createButton(Material.LIME_STAINED_GLASS_PANE, plugin.tr("gui.purchase.continue"),
                plugin.tr("gui.purchase.continue.desc")));
        inventory.setItem(CANCEL_SLOT, createButton(Material.BARRIER, plugin.tr("gui.common.cancel"),
                plugin.tr("gui.purchase.cancel.desc")));
    }

    private void refreshAmountStage() {
        inventory.setItem(BACK_SLOT, createButton(Material.ARROW, plugin.tr("gui.purchase.amount.back"),
                plugin.tr("gui.purchase.amount.back.desc")));
        inventory.setItem(AMOUNT_DOWN_SLOT, createButton(Material.RED_STAINED_GLASS_PANE, plugin.tr("gui.purchase.amount.down"),
                plugin.tr("gui.purchase.amount.left_minus"),
                plugin.tr("gui.purchase.amount.right_minus"),
                plugin.tr("gui.purchase.amount.shift_minus")));
        inventory.setItem(SELECTED_SLOT, buildSelectedAmountItem());
        inventory.setItem(AMOUNT_UP_SLOT, createButton(Material.LIME_STAINED_GLASS_PANE, plugin.tr("gui.purchase.amount.up"),
                plugin.tr("gui.purchase.amount.left_plus"),
                plugin.tr("gui.purchase.amount.right_plus"),
                plugin.tr("gui.purchase.amount.shift_plus")));
        inventory.setItem(SEARCH_SLOT, createButton(Material.BOOK, plugin.tr("gui.purchase.step2.title"),
                plugin.tr("gui.purchase.step2.desc")));
        inventory.setItem(ACTION_SLOT, createButton(Material.EMERALD, plugin.tr("gui.purchase.step3.title"),
                plugin.tr("gui.purchase.step3.desc1"),
                plugin.tr("gui.purchase.step3.desc2")));
        inventory.setItem(CANCEL_SLOT, createButton(Material.BARRIER, plugin.tr("gui.common.cancel"),
                plugin.tr("gui.purchase.cancel.desc")));
    }

    private void handleBlockStageClick(int rawSlot) {
        List<Material> filteredBlocks = getFilteredBlocks();
        if (rawSlot < ITEMS_PER_PAGE) {
            int blockIndex = currentPage * ITEMS_PER_PAGE + rawSlot;
            if (blockIndex < filteredBlocks.size()) {
                selectedMaterial = filteredBlocks.get(blockIndex);
                plugin.openPurchaseSetup(viewer, Stage.AMOUNT, selectedMaterial, amount, searchQuery);
            }
            return;
        }

        if (rawSlot == PREV_SLOT) {
            currentPage--;
            refresh();
            return;
        }
        if (rawSlot == NEXT_SLOT) {
            currentPage++;
            refresh();
            return;
        }
        if (rawSlot == SEARCH_SLOT) {
            plugin.promptPurchaseBlockSearch(viewer, selectedMaterial, amount, searchQuery);
            return;
        }
        if (rawSlot == ACTION_SLOT) {
            plugin.openPurchaseSetup(viewer, Stage.AMOUNT, selectedMaterial, amount, searchQuery);
            return;
        }
        if (rawSlot == CANCEL_SLOT) {
            viewer.closeInventory();
            plugin.sendMessage(viewer, plugin.trc("purchase_setup.cancelled", NamedTextColor.YELLOW));
        }
    }

    private void handleAmountStageClick(InventoryClickEvent event, int rawSlot) {
        if (rawSlot == BACK_SLOT) {
            plugin.openPurchaseSetup(viewer, Stage.BLOCK, selectedMaterial, amount, searchQuery);
            return;
        }
        if (rawSlot == AMOUNT_DOWN_SLOT) {
            changeAmount(event, false);
            return;
        }
        if (rawSlot == AMOUNT_UP_SLOT) {
            changeAmount(event, true);
            return;
        }
        if (rawSlot == ACTION_SLOT) {
            plugin.promptPurchaseTotalPrice(viewer, selectedMaterial, amount, searchQuery);
            return;
        }
        if (rawSlot == CANCEL_SLOT) {
            viewer.closeInventory();
            plugin.sendMessage(viewer, plugin.trc("purchase_setup.cancelled", NamedTextColor.YELLOW));
        }
    }

    private void changeAmount(InventoryClickEvent event, boolean increase) {
        int delta;
        if (event.isShiftClick()) {
            delta = 64;
        } else if (event.isRightClick()) {
            delta = 16;
        } else {
            delta = 1;
        }
        amount = Math.max(1, amount + (increase ? delta : -delta));
        refresh();
    }

    private ItemStack buildSelectedBlockItem() {
        ItemStack selected = createSafeItemStack(selectedMaterial, 1);
        if (selected == null) {
            selectedMaterial = Material.STONE;
            selected = new ItemStack(Material.STONE);
        }
        ItemMeta meta = selected.getItemMeta();
        if (meta != null) {
            AdventureSupport.displayName(meta, Component.text(plugin.tr("gui.purchase.selected.block", MarketGui.getItemName(selected))).color(NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(plugin.tr("gui.purchase.selected.amount", amount)).color(NamedTextColor.WHITE));
            if (searchQuery != null) {
                lore.add(Component.text(plugin.tr("gui.purchase.selected.search", searchQuery)).color(NamedTextColor.GRAY));
            }
            lore.add(Component.text(plugin.tr("gui.purchase.selected.change_hint")).color(NamedTextColor.GRAY));
            AdventureSupport.lore(meta, lore);
            selected.setItemMeta(meta);
        }
        return selected;
    }

    private ItemStack buildSelectedAmountItem() {
        ItemStack selected = createSafeItemStack(selectedMaterial, Math.min(amount, Math.max(1, selectedMaterial.getMaxStackSize())));
        if (selected == null) {
            selected = new ItemStack(Material.STONE);
        }
        ItemMeta meta = selected.getItemMeta();
        if (meta != null) {
            AdventureSupport.displayName(meta, Component.text(plugin.tr("gui.purchase.selected.block", MarketGui.getItemName(new ItemStack(selectedMaterial))))
                    .color(NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(plugin.tr("gui.purchase.amount.buy", amount)).color(NamedTextColor.WHITE));
            lore.add(Component.text(plugin.tr("gui.purchase.amount.next")).color(NamedTextColor.YELLOW));
            AdventureSupport.lore(meta, lore);
            selected.setItemMeta(meta);
        }
        return selected;
    }

    private List<Material> getFilteredBlocks() {
        if (searchQuery == null) {
            return BLOCKS;
        }
        List<Material> filtered = new ArrayList<>();
        for (Material material : BLOCKS) {
            if (matchesSearch(material, searchQuery)) {
                filtered.add(material);
            }
        }
        return filtered;
    }

    private static boolean matchesSearch(Material material, String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return true;
        }
        String materialName = normalize(material.name().replace('_', ' '));
        if (materialName.contains(normalizedQuery)) {
            return true;
        }
        String displayName = normalize(MarketGui.getItemName(new ItemStack(material)));
        if (displayName.contains(normalizedQuery)) {
            return true;
        }
        String key = normalize(material.getKey().toString().replace(':', ' '));
        return key.contains(normalizedQuery);
    }

    private static String normalize(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean isSelectableBlock(Material material) {
        return material != null
                && material.isBlock()
                && material.isItem()
                && !material.isAir()
                && canCreateItemStack(material);
    }

    private static int findPageFor(Material material, String searchQuery) {
        List<Material> source = searchQuery == null ? BLOCKS : BLOCKS.stream()
                .filter(block -> matchesSearch(block, searchQuery))
                .toList();
        int index = source.indexOf(material);
        if (index < 0) {
            return 0;
        }
        return index / ITEMS_PER_PAGE;
    }

    private static ItemStack createButton(Material material, String title, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            AdventureSupport.displayName(meta, Component.text(title).color(NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>(loreLines.length);
            for (String line : loreLines) {
                lore.add(Component.text(line).color(NamedTextColor.GRAY));
            }
            AdventureSupport.lore(meta, lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static boolean canCreateItemStack(Material material) {
        return createSafeItemStack(material, 1) != null;
    }

    private static ItemStack createSafeItemStack(Material material, int amount) {
        try {
            return new ItemStack(material, amount);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}



