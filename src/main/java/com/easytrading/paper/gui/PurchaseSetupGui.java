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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The search-result screen in the chat-first purchase-order wizard. */
public final class PurchaseSetupGui {
    private static final int TOTAL_SLOTS = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEW_SEARCH_SLOT = 48;
    private static final int INFO_SLOT = 49;
    private static final int CANCEL_SLOT = 50;
    private static final int NEXT_SLOT = 53;

    private static final List<Material> ITEMS = Arrays.stream(Material.values())
            .filter(Material::isItem)
            .filter(material -> !material.isAir())
            .filter(PurchaseSetupGui::canCreateItemStack)
            .toList();

    private final EasyTradingPlugin plugin;
    private final Player viewer;
    private final String query;
    private final List<Match> matches;
    private int currentPage;
    private Inventory inventory;

    public PurchaseSetupGui(EasyTradingPlugin plugin, Player viewer, String query) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.query = query == null ? "" : query.trim();
        this.matches = findMatches(plugin, this.query);
    }

    public void open() {
        inventory = Bukkit.createInventory(null, TOTAL_SLOTS,
                AdventureSupport.legacy(Component.text(plugin.tr("gui.purchase.results.title"))
                        .color(NamedTextColor.DARK_GREEN).decoration(TextDecoration.BOLD, true)));
        refresh();
        viewer.openInventory(inventory);
    }

    public Inventory getInventory() { return inventory; }

    public void handleClose() { plugin.removePurchaseSetupGui(viewer); }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= TOTAL_SLOTS) return;
        if (slot < ITEMS_PER_PAGE) {
            int index = currentPage * ITEMS_PER_PAGE + slot;
            if (index < matches.size()) plugin.promptPurchaseAmount(viewer, matches.get(index).material(), query);
            return;
        }
        if (slot == PREVIOUS_SLOT && currentPage > 0) {
            currentPage--;
            refresh();
        } else if (slot == NEXT_SLOT && currentPage < getTotalPages() - 1) {
            currentPage++;
            refresh();
        } else if (slot == NEW_SEARCH_SLOT) {
            plugin.promptPurchaseItemSearch(viewer, query);
        } else if (slot == CANCEL_SLOT) {
            plugin.cancelPurchaseSetup(viewer);
        }
    }

    private void refresh() {
        inventory.clear();
        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, matches.size());
        for (int slot = 0, index = start; index < end; slot++, index++) {
            Match match = matches.get(index);
            ItemStack item = new ItemStack(match.material());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                AdventureSupport.displayName(meta, Component.text(plugin.getLocalization().itemName(match.material()))
                        .color(NamedTextColor.GOLD));
                AdventureSupport.lore(meta, List.of(
                        Component.text(match.material().getKey().toString()).color(NamedTextColor.DARK_GRAY),
                        Component.text(plugin.tr("gui.purchase.results.select")).color(NamedTextColor.GRAY)));
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
        }
        if (matches.isEmpty()) inventory.setItem(22, createButton(Material.BARRIER,
                plugin.tr("gui.purchase.results.empty"), plugin.tr("gui.purchase.results.empty_hint")));
        if (currentPage > 0) inventory.setItem(PREVIOUS_SLOT, createButton(Material.ARROW,
                plugin.tr("gui.common.back"), plugin.tr("gui.common.page.previous")));
        if (currentPage < getTotalPages() - 1) inventory.setItem(NEXT_SLOT, createButton(Material.ARROW,
                plugin.tr("gui.common.forward"), plugin.tr("gui.common.page.next")));
        inventory.setItem(NEW_SEARCH_SLOT, createButton(Material.NAME_TAG,
                plugin.tr("gui.purchase.results.new_search"), plugin.tr("gui.purchase.results.new_search_hint")));
        inventory.setItem(INFO_SLOT, createButton(Material.BOOK, plugin.tr("gui.purchase.results.query", query),
                plugin.tr("gui.purchase.results.count", matches.size()),
                plugin.tr("gui.purchase.results.page", currentPage + 1, getTotalPages())));
        inventory.setItem(CANCEL_SLOT, createButton(Material.BARRIER, plugin.tr("gui.common.cancel"),
                plugin.tr("gui.purchase.cancel.desc")));
    }

    private int getTotalPages() { return Math.max(1, (matches.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE); }

    /** Searches display names and Minecraft IDs, tolerating ordinary typos. */
    private static List<Match> findMatches(EasyTradingPlugin plugin, String query) {
        List<String> queryTokens = tokens(query);
        List<Match> results = new ArrayList<>();
        for (Material material : ITEMS) {
            String localizedName = plugin.getLocalization().itemName(material);
            List<String> candidateTokens = tokens(localizedName + " " + material.getKey().toString().replace(':', ' '));
            int score = score(queryTokens, candidateTokens);
            if (score >= 0) results.add(new Match(material, score, normalize(localizedName)));
        }
        results.sort(Comparator.comparingInt(Match::score).thenComparing(Match::name)
                .thenComparing(match -> match.material().name()));
        return results;
    }

    private static int score(List<String> queryTokens, List<String> candidateTokens) {
        if (queryTokens.isEmpty()) return -1;
        int total = 0;
        for (String queryToken : queryTokens) {
            int best = Integer.MAX_VALUE;
            for (String candidateToken : candidateTokens) {
                if (candidateToken.equals(queryToken)) best = 0;
                else if (candidateToken.startsWith(queryToken)) best = Math.min(best, 1);
                else if (candidateToken.contains(queryToken)) best = Math.min(best, 2);
                else {
                    int distance = damerauLevenshtein(queryToken, candidateToken);
                    int allowed = queryToken.length() <= 4 ? 1 : Math.max(1, queryToken.length() / 3);
                    if (distance <= allowed) best = Math.min(best, 3 + distance);
                }
            }
            if (best == Integer.MAX_VALUE) return -1;
            total += best;
        }
        return total;
    }

    private static List<String> tokens(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return List.of();
        return Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+")).filter(token -> !token.isBlank()).toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static int damerauLevenshtein(String left, String right) {
        int[][] distances = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) distances[i][0] = i;
        for (int j = 0; j <= right.length(); j++) distances[0][j] = j;
        for (int i = 1; i <= left.length(); i++) for (int j = 1; j <= right.length(); j++) {
            int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
            int value = Math.min(Math.min(distances[i - 1][j] + 1, distances[i][j - 1] + 1),
                    distances[i - 1][j - 1] + cost);
            if (i > 1 && j > 1 && left.charAt(i - 1) == right.charAt(j - 2)
                    && left.charAt(i - 2) == right.charAt(j - 1)) value = Math.min(value, distances[i - 2][j - 2] + cost);
            distances[i][j] = value;
        }
        return distances[left.length()][right.length()];
    }

    private static ItemStack createButton(Material material, String title, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            AdventureSupport.displayName(meta, Component.text(title).color(NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            for (String loreLine : loreLines) lore.add(Component.text(loreLine).color(NamedTextColor.GRAY));
            AdventureSupport.lore(meta, lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static boolean canCreateItemStack(Material material) {
        try { new ItemStack(material); return true; } catch (IllegalArgumentException ignored) { return false; }
    }

    private record Match(Material material, int score, String name) {}
}
