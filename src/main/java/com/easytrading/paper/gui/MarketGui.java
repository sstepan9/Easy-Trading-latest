package com.easytrading.paper.gui;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.data.MarketBankConfig;
import com.easytrading.paper.data.MarketData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Chest-based market GUI replicating the Fabric MarketMenu.
 * 6 rows (54 slots): rows 1-5 = listings (45 items), row 6 = navigation.
 */
public class MarketGui implements Listener {
    private static final int TOTAL_SLOTS = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int NAV_ROW_START = 45;

    private static final Set<MarketGui> OPEN_GUIS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private final EasyTradingPlugin plugin;
    private final Player viewer;
    private Inventory inventory;
    private int currentPage = 0;
    private int totalPages = 1;
    private final int[] slotToListingId = new int[TOTAL_SLOTS];

    public MarketGui(EasyTradingPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        Arrays.fill(slotToListingId, -1);
    }

    public void open() {
        inventory = Bukkit.createInventory(null, TOTAL_SLOTS,
                Component.text("Market").color(NamedTextColor.DARK_GREEN).decoration(TextDecoration.BOLD, true));
        OPEN_GUIS.add(this);
        refresh();
        viewer.openInventory(inventory);
    }

    public void refresh() {
        Arrays.fill(slotToListingId, -1);
        inventory.clear();

        List<MarketData.Listing> allListings = plugin.getMarketData().getAllListingsSorted();
        totalPages = Math.max(1, (allListings.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        // Compute averages across ALL listings
        Map<Material, Long> totalPriceByItem = new HashMap<>();
        Map<Material, Integer> totalCountByItem = new HashMap<>();
        Map<Material, Integer> listingsByItem = new HashMap<>();
        for (MarketData.Listing listing : allListings) {
            Material mat = listing.stack.getType();
            totalPriceByItem.merge(mat, listing.price, Long::sum);
            totalCountByItem.merge(mat, listing.stack.getAmount(), Integer::sum);
            listingsByItem.merge(mat, 1, Integer::sum);
        }

        // Show items for current page
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, allListings.size());
        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            MarketData.Listing listing = allListings.get(i);
            String sellerName = resolveSellerName(listing.seller);
            Material mat = listing.stack.getType();
            int listingCount = listingsByItem.getOrDefault(mat, 0);
            int totalCount = totalCountByItem.getOrDefault(mat, 0);
            long avgPrice = totalCount > 0 ? (totalPriceByItem.getOrDefault(mat, 0L) / totalCount) : 0L;

            ItemStack display = listing.stack.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Price: " + listing.price).color(NamedTextColor.GOLD));
                if (listingCount > 1) {
                    lore.add(Component.text("Average price per item: " + avgPrice).color(NamedTextColor.GRAY));
                }
                lore.add(Component.text("Seller: " + sellerName).color(NamedTextColor.AQUA));
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            inventory.setItem(slot, display);
            slotToListingId[slot] = listing.id;
            slot++;
        }

        // Navigation row
        setupNavRow(allListings.size());

        // Send stats in chat/action bar
        sendStats(allListings, totalPriceByItem, totalCountByItem);
    }

    private void setupNavRow(int totalListings) {
        if (currentPage > 0) {
            ItemStack prevArrow = new ItemStack(Material.ARROW, 1);
            ItemMeta meta = prevArrow.getItemMeta();
            meta.displayName(Component.text("< Back").color(NamedTextColor.YELLOW));
            meta.lore(List.of(Component.text("Previous page").color(NamedTextColor.GRAY)));
            prevArrow.setItemMeta(meta);
            inventory.setItem(NAV_ROW_START, prevArrow);
        }

        ItemStack pageInfo = new ItemStack(Material.PAPER, Math.max(1, currentPage + 1));
        ItemMeta pageMeta = pageInfo.getItemMeta();
        pageMeta.displayName(Component.text(String.format("Page %d/%d (%d listings)", currentPage + 1, totalPages, totalListings))
                .color(NamedTextColor.WHITE));
        pageInfo.setItemMeta(pageMeta);
        inventory.setItem(NAV_ROW_START + 4, pageInfo);

        if (currentPage < totalPages - 1) {
            ItemStack nextArrow = new ItemStack(Material.ARROW, 1);
            ItemMeta meta = nextArrow.getItemMeta();
            meta.displayName(Component.text("Forward >").color(NamedTextColor.YELLOW));
            meta.lore(List.of(Component.text("Next page").color(NamedTextColor.GRAY)));
            nextArrow.setItemMeta(meta);
            inventory.setItem(NAV_ROW_START + 8, nextArrow);
        }

        // Bank rates info item (slot 46 = row 6, col 1)
        MarketBankConfig bankConfig = plugin.getBankConfig();
        bankConfig.ensureLoaded();
        List<MarketBankConfig.BankRateEntry> rates = bankConfig.buildRatesList();
        if (!rates.isEmpty()) {
            ItemStack ratesItem = new ItemStack(Material.GOLD_INGOT, 1);
            ItemMeta ratesMeta = ratesItem.getItemMeta();
            ratesMeta.displayName(Component.text("Bank Rates").color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            List<Component> rateLore = new ArrayList<>();
            rateLore.add(Component.text("Tax: " + bankConfig.getTaxPercent() + "%").color(NamedTextColor.RED));
            rateLore.add(Component.text("Rates (buy/sell):").color(NamedTextColor.GRAY));
            for (MarketBankConfig.BankRateEntry rate : rates) {
                String name = MarketBankConfig.getDisplayName(rate.itemId());
                rateLore.add(Component.text(String.format("  %s: %d / %d", name, rate.buyPrice(), rate.sellPrice()))
                        .color(NamedTextColor.WHITE));
            }
            ratesMeta.lore(rateLore);
            ratesItem.setItemMeta(ratesMeta);
            inventory.setItem(NAV_ROW_START + 1, ratesItem);
        }
    }

    private void sendStats(List<MarketData.Listing> allListings,
                           Map<Material, Long> totalPriceByItem,
                           Map<Material, Integer> totalCountByItem) {
        long totalTurnover = 0L;
        for (long price : totalPriceByItem.values()) totalTurnover += price;
        long avgTurnover = 0L;
        for (var entry : totalPriceByItem.entrySet()) {
            int totalCount = totalCountByItem.getOrDefault(entry.getKey(), 0);
            if (totalCount > 0) avgTurnover += entry.getValue() / totalCount;
        }
        long capitalization = plugin.getMarketData().getTotalSales();

        viewer.sendActionBar(Component.text(String.format(
                "Turnover: %d | Avg: %d | Cap: %d", totalTurnover, avgTurnover, capitalization
        )).color(NamedTextColor.GOLD));
    }

    private String resolveSellerName(UUID seller) {
        Player online = Bukkit.getPlayer(seller);
        if (online != null) return online.getName();
        // Try offline player
        String name = Bukkit.getOfflinePlayer(seller).getName();
        return name != null ? name : seller.toString();
    }

    public Inventory getInventory() { return inventory; }
    public Player getViewer() { return viewer; }

    // ── Event Handling ──

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slotIndex = event.getRawSlot();
        if (slotIndex < 0 || slotIndex >= TOTAL_SLOTS) return;

        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (slotIndex >= NAV_ROW_START) {
            int navSlot = slotIndex - NAV_ROW_START;
            if (navSlot == 0 && currentPage > 0) {
                currentPage--;
                refresh();
            } else if (navSlot == 8 && currentPage < totalPages - 1) {
                currentPage++;
                refresh();
            }
            return;
        }

        int listingId = slotToListingId[slotIndex];
        if (listingId != -1) {
            handleListingClick(player, listingId);
        }
    }

    private void handleListingClick(Player buyer, int listingId) {
        Optional<MarketData.Listing> listingOpt = plugin.getMarketData().getListing(listingId);
        if (listingOpt.isEmpty()) {
            buyer.sendMessage(Component.text("This listing has already been sold.").color(NamedTextColor.RED));
            return;
        }

        MarketData.Listing listing = listingOpt.get();

        // If seller clicks own listing — remove it
        if (listing.seller.equals(buyer.getUniqueId())) {
            plugin.handleBuyConfirm(buyer, true, listingId);
            refresh();
            refreshAll();
            return;
        }

        // Open confirmation GUI
        new ConfirmationGui(plugin, buyer, ConfirmationGui.Type.BUY, listingId,
                getItemName(listing.stack), listing.stack.getAmount(), listing.price, 0,
                resolveSellerName(listing.seller), 0).open();
    }

    public void handleClose() {
        OPEN_GUIS.remove(this);
    }

    // ── Static Methods ──

    public static void refreshAll() {
        MarketGui[] snapshot;
        synchronized (OPEN_GUIS) {
            snapshot = OPEN_GUIS.toArray(new MarketGui[0]);
        }
        for (MarketGui gui : snapshot) {
            gui.refresh();
        }
    }

    public static String getItemName(ItemStack stack) {
        if (stack == null) return "Unknown";
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(meta.displayName());
        }
        // Convert material name to readable form
        String name = stack.getType().name().toLowerCase().replace('_', ' ');
        // Capitalize first letter of each word
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!sb.isEmpty()) sb.append(' ');
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }
}
