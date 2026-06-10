package com.easytrading.paper.gui;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.util.AdventureSupport;
import com.easytrading.paper.data.MarketData;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Chest-based market GUI with selling and purchasing views.
 */
public class MarketGui {
    private static final int TOTAL_SLOTS = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int NAV_ROW_START = 45;

    private static final int PREV_SLOT = NAV_ROW_START;
    private static final int SORT_SLOT = NAV_ROW_START + 1;
    private static final int OWN_SLOT = NAV_ROW_START + 2;
    private static final int CLEAR_SLOT = NAV_ROW_START + 3;
    private static final int PAGE_SLOT = NAV_ROW_START + 4;
    private static final int ITEM_FILTER_SLOT = NAV_ROW_START + 5;
    private static final int SELLER_FILTER_SLOT = NAV_ROW_START + 6;
    private static final int MODE_SLOT = NAV_ROW_START + 7;
    private static final int NEXT_SLOT = NAV_ROW_START + 8;

    private static final Set<MarketGui> OPEN_GUIS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    public enum SortMode {
        NEWEST("gui.market.sort.newest"),
        CHEAPEST("gui.market.sort.cheapest"),
        EXPENSIVE("gui.market.sort.expensive"),
        OLDEST("gui.market.sort.oldest");

        private final String labelKey;

        SortMode(String labelKey) {
            this.labelKey = labelKey;
        }

        public String getLabel(EasyTradingPlugin plugin) {
            return plugin.tr(labelKey);
        }

        public SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum DisplayMode {
        SELLING,
        PURCHASING;

        public DisplayMode toggle() {
            return this == SELLING ? PURCHASING : SELLING;
        }
    }

    public record ViewOptions(UUID sellerFilter, String sellerLabel, String itemQuery, SortMode sortMode,
                              DisplayMode displayMode) {
        public static ViewOptions defaults() {
            return new ViewOptions(null, null, null, SortMode.NEWEST, DisplayMode.SELLING);
        }

        public ViewOptions {
            sortMode = sortMode == null ? SortMode.NEWEST : sortMode;
            displayMode = displayMode == null ? DisplayMode.SELLING : displayMode;
            itemQuery = itemQuery == null ? null : itemQuery.trim();
            if (itemQuery != null && itemQuery.isEmpty()) {
                itemQuery = null;
            }
            sellerLabel = sellerLabel == null ? null : sellerLabel.trim();
            if (sellerLabel != null && sellerLabel.isEmpty()) {
                sellerLabel = null;
            }
        }

        public boolean hasFilters() {
            return sellerFilter != null || itemQuery != null;
        }

        public boolean isOwnListings(UUID viewerId) {
            return sellerFilter != null && sellerFilter.equals(viewerId);
        }

        public ViewOptions withSortMode(SortMode nextSortMode) {
            return new ViewOptions(sellerFilter, sellerLabel, itemQuery, nextSortMode, displayMode);
        }

        public ViewOptions withSellerFilter(UUID nextSellerFilter, String nextSellerLabel) {
            return new ViewOptions(nextSellerFilter, nextSellerLabel, itemQuery, sortMode, displayMode);
        }

        public ViewOptions withItemQuery(String nextItemQuery) {
            return new ViewOptions(sellerFilter, sellerLabel, nextItemQuery, sortMode, displayMode);
        }

        public ViewOptions withDisplayMode(DisplayMode nextDisplayMode) {
            return new ViewOptions(sellerFilter, sellerLabel, itemQuery, sortMode, nextDisplayMode);
        }

        public ViewOptions toggleOwnListings(Player viewer) {
            if (isOwnListings(viewer.getUniqueId())) {
                return new ViewOptions(null, null, itemQuery, sortMode, displayMode);
            }
            return new ViewOptions(viewer.getUniqueId(), viewer.getName(), itemQuery, sortMode, displayMode);
        }

        public ViewOptions clearFilters() {
            return new ViewOptions(null, null, null, sortMode, displayMode);
        }
    }

    private final EasyTradingPlugin plugin;
    private final Player viewer;
    private ViewOptions viewOptions;
    private Inventory inventory;
    private int currentPage = 0;
    private int totalPages = 1;
    private final int[] slotToListingId = new int[TOTAL_SLOTS];

    public MarketGui(EasyTradingPlugin plugin, Player viewer) {
        this(plugin, viewer, ViewOptions.defaults());
    }

    public MarketGui(EasyTradingPlugin plugin, Player viewer, ViewOptions viewOptions) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.viewOptions = viewOptions == null ? ViewOptions.defaults() : viewOptions;
        Arrays.fill(slotToListingId, -1);
    }

    public void open() {
        String title = viewOptions.displayMode() == DisplayMode.PURCHASING
                ? plugin.tr("gui.market.title.purchasing")
                : plugin.tr("gui.market.title.selling");
        inventory = Bukkit.createInventory(null, TOTAL_SLOTS,
                AdventureSupport.legacy(Component.text(title).color(NamedTextColor.DARK_GREEN).decoration(TextDecoration.BOLD, true)));
        OPEN_GUIS.add(this);
        refresh();
        viewer.openInventory(inventory);
    }

    public void refresh() {
        Arrays.fill(slotToListingId, -1);
        inventory.clear();

        if (viewOptions.displayMode() == DisplayMode.PURCHASING) {
            refreshPurchaseOrders();
        } else {
            refreshListings();
        }
    }

    private void refreshListings() {
        List<MarketData.Listing> allListings = plugin.getMarketData().getAllListingsSorted();
        List<MarketData.Listing> filteredListings = applyListingViewOptions(allListings);
        totalPages = Math.max(1, (filteredListings.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        Map<Material, Long> totalPriceByItem = new HashMap<>();
        Map<Material, Integer> totalCountByItem = new HashMap<>();
        Map<Material, Integer> listingsByItem = new HashMap<>();
        for (MarketData.Listing listing : filteredListings) {
            Material mat = listing.stack.getType();
            totalPriceByItem.merge(mat, listing.price, Long::sum);
            totalCountByItem.merge(mat, listing.stack.getAmount(), Integer::sum);
            listingsByItem.merge(mat, 1, Integer::sum);
        }

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filteredListings.size());
        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            MarketData.Listing listing = filteredListings.get(i);
            String sellerName = resolveSellerName(listing.seller);
            Material mat = listing.stack.getType();
            int listingCount = listingsByItem.getOrDefault(mat, 0);
            int totalCount = totalCountByItem.getOrDefault(mat, 0);
            long avgPrice = totalCount > 0 ? totalPriceByItem.getOrDefault(mat, 0L) / totalCount : 0L;

            ItemStack display = listing.stack.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(plugin.tr("gui.market.listing.id", listing.id)).color(NamedTextColor.DARK_GRAY));
                lore.add(Component.text(plugin.tr("gui.market.listing.price", listing.price)).color(NamedTextColor.GOLD));
                if (listingCount > 1) {
                    lore.add(Component.text(plugin.tr("gui.market.listing.avg_price", avgPrice)).color(NamedTextColor.GRAY));
                }
                lore.add(Component.text(plugin.tr("gui.market.listing.seller", sellerName)).color(NamedTextColor.AQUA));
                lore.add(Component.text(plugin.tr("gui.market.listing.listed", formatAge(listing.createdAt))).color(NamedTextColor.GRAY));
                if (listing.seller.equals(viewer.getUniqueId())) {
                    lore.add(Component.text(plugin.tr("gui.market.listing.remove_hint")).color(NamedTextColor.YELLOW));
                } else {
                    lore.add(Component.text(plugin.tr("gui.market.listing.buy_hint")).color(NamedTextColor.GREEN));
                }
                AdventureSupport.lore(meta, lore);
                display.setItemMeta(meta);
            }
            inventory.setItem(slot, display);
            slotToListingId[slot] = listing.id;
            slot++;
        }

        if (filteredListings.isEmpty()) {
            renderEmptyState(plugin.tr("gui.market.empty.listings"));
        }

        setupNavRow(filteredListings.size(), allListings.size());
        sendListingStats(filteredListings, totalPriceByItem, totalCountByItem);
    }

    private void refreshPurchaseOrders() {
        List<MarketData.PurchaseOrder> allOrders = plugin.getMarketData().getAllPurchaseOrdersSorted();
        List<MarketData.PurchaseOrder> filteredOrders = applyPurchaseViewOptions(allOrders);
        totalPages = Math.max(1, (filteredOrders.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filteredOrders.size());
        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            MarketData.PurchaseOrder order = filteredOrders.get(i);
            int available = plugin.countMatchingRequestedItems(viewer, order.stack);
            boolean canSell = available >= order.stack.getAmount();

            ItemStack display = order.stack.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(plugin.tr("gui.market.order.id", order.id)).color(NamedTextColor.DARK_GRAY));
                lore.add(Component.text(plugin.tr("gui.market.order.total_price", order.totalPrice)).color(NamedTextColor.GOLD));
                lore.add(Component.text(plugin.tr("gui.market.order.requested", order.stack.getAmount())).color(NamedTextColor.WHITE));
                lore.add(Component.text(plugin.tr("gui.market.order.buyer", resolveSellerName(order.buyer))).color(NamedTextColor.AQUA));
                lore.add(Component.text(plugin.tr("gui.market.order.created", formatAge(order.createdAt))).color(NamedTextColor.GRAY));
                if (order.buyer.equals(viewer.getUniqueId())) {
                    lore.add(Component.text(plugin.tr("gui.market.order.cancel_hint")).color(NamedTextColor.YELLOW));
                } else if (canSell) {
                    lore.add(Component.text(plugin.tr("gui.market.order.enough_items")).color(NamedTextColor.GREEN));
                    lore.add(Component.text(plugin.tr("gui.market.order.sell_hint")).color(NamedTextColor.GREEN));
                } else {
                    lore.add(Component.text(plugin.tr("gui.market.order.need_have", order.stack.getAmount(), available))
                            .color(NamedTextColor.RED));
                }
                AdventureSupport.lore(meta, lore);
                display.setItemMeta(meta);
            }
            inventory.setItem(slot, display);
            slotToListingId[slot] = order.id;
            slot++;
        }

        if (filteredOrders.isEmpty()) {
            renderEmptyState(plugin.tr("gui.market.empty.orders"));
        }

        setupNavRow(filteredOrders.size(), allOrders.size());
        sendPurchaseStats(filteredOrders);
    }

    private List<MarketData.Listing> applyListingViewOptions(List<MarketData.Listing> listings) {
        List<MarketData.Listing> filtered = new ArrayList<>();
        for (MarketData.Listing listing : listings) {
            if (viewOptions.sellerFilter() != null && !viewOptions.sellerFilter().equals(listing.seller)) {
                continue;
            }
            if (viewOptions.itemQuery() != null && !matchesItemQuery(listing.stack, viewOptions.itemQuery())) {
                continue;
            }
            filtered.add(listing);
        }

        Comparator<MarketData.Listing> comparator = switch (viewOptions.sortMode()) {
            case CHEAPEST -> Comparator.comparingLong((MarketData.Listing listing) -> listing.price)
                    .thenComparingLong(listing -> listing.createdAt)
                    .thenComparingInt(listing -> listing.id);
            case EXPENSIVE -> Comparator.comparingLong((MarketData.Listing listing) -> listing.price)
                    .reversed()
                    .thenComparingLong(listing -> listing.createdAt)
                    .thenComparingInt(listing -> listing.id);
            case OLDEST -> Comparator.comparingLong((MarketData.Listing listing) -> listing.createdAt)
                    .thenComparingInt(listing -> listing.id);
            case NEWEST -> Comparator.comparingLong((MarketData.Listing listing) -> listing.createdAt)
                    .thenComparingInt(listing -> listing.id)
                    .reversed();
        };
        filtered.sort(comparator);
        return filtered;
    }

    private List<MarketData.PurchaseOrder> applyPurchaseViewOptions(List<MarketData.PurchaseOrder> orders) {
        List<MarketData.PurchaseOrder> filtered = new ArrayList<>();
        for (MarketData.PurchaseOrder order : orders) {
            if (viewOptions.sellerFilter() != null && !viewOptions.sellerFilter().equals(order.buyer)) {
                continue;
            }
            if (viewOptions.itemQuery() != null && !matchesItemQuery(order.stack, viewOptions.itemQuery())) {
                continue;
            }
            filtered.add(order);
        }

        Comparator<MarketData.PurchaseOrder> comparator = switch (viewOptions.sortMode()) {
            case CHEAPEST -> Comparator.comparingLong((MarketData.PurchaseOrder order) -> order.totalPrice)
                    .thenComparingLong(order -> order.createdAt)
                    .thenComparingInt(order -> order.id);
            case EXPENSIVE -> Comparator.comparingLong((MarketData.PurchaseOrder order) -> order.totalPrice)
                    .reversed()
                    .thenComparingLong(order -> order.createdAt)
                    .thenComparingInt(order -> order.id);
            case OLDEST -> Comparator.comparingLong((MarketData.PurchaseOrder order) -> order.createdAt)
                    .thenComparingInt(order -> order.id);
            case NEWEST -> Comparator.comparingLong((MarketData.PurchaseOrder order) -> order.createdAt)
                    .thenComparingInt(order -> order.id)
                    .reversed();
        };
        filtered.sort(comparator);
        return filtered;
    }

    private boolean matchesItemQuery(ItemStack stack, String itemQuery) {
        String normalizedQuery = normalize(itemQuery);
        if (normalizedQuery.isEmpty()) {
            return true;
        }

        String displayName = normalize(getItemName(stack));
        if (displayName.contains(normalizedQuery)) {
            return true;
        }

        String materialName = normalize(stack.getType().name().replace('_', ' '));
        if (materialName.contains(normalizedQuery)) {
            return true;
        }

        String materialKey = normalize(stack.getType().getKey().toString().replace(':', ' '));
        return materialKey.contains(normalizedQuery);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
    }

    private void renderEmptyState(String title) {
        ItemStack empty = new ItemStack(Material.BARRIER);
        ItemMeta meta = empty.getItemMeta();
        if (meta != null) {
            AdventureSupport.displayName(meta, Component.text(title).color(NamedTextColor.RED));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(plugin.tr("gui.market.empty.adjust")).color(NamedTextColor.GRAY));
            if (viewOptions.hasFilters()) {
                lore.add(Component.text(plugin.tr("gui.market.empty.clear")).color(NamedTextColor.GRAY));
            }
            AdventureSupport.lore(meta, lore);
            empty.setItemMeta(meta);
        }
        inventory.setItem(22, empty);
    }

    private void setupNavRow(int filteredCount, int totalOffers) {
        if (currentPage > 0) {
            ItemStack prevArrow = new ItemStack(Material.ARROW, 1);
            ItemMeta meta = prevArrow.getItemMeta();
            AdventureSupport.displayName(meta, Component.text(plugin.tr("gui.common.back")).color(NamedTextColor.YELLOW));
            AdventureSupport.lore(meta, List.of(Component.text(plugin.tr("gui.common.page.previous")).color(NamedTextColor.GRAY)));
            prevArrow.setItemMeta(meta);
            inventory.setItem(PREV_SLOT, prevArrow);
        }

        ItemStack sortItem = new ItemStack(Material.HOPPER);
        ItemMeta sortMeta = sortItem.getItemMeta();
        AdventureSupport.displayName(sortMeta,
                Component.text(plugin.tr("gui.market.sort.title", viewOptions.sortMode().getLabel(plugin))).color(NamedTextColor.YELLOW));
        AdventureSupport.lore(sortMeta, List.of(Component.text(plugin.tr("gui.market.sort.hint")).color(NamedTextColor.GRAY)));
        sortItem.setItemMeta(sortMeta);
        inventory.setItem(SORT_SLOT, sortItem);

        boolean ownOnly = viewOptions.isOwnListings(viewer.getUniqueId());
        ItemStack ownItem = new ItemStack(ownOnly ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta ownMeta = ownItem.getItemMeta();
        String ownLabel = viewOptions.displayMode() == DisplayMode.PURCHASING
                ? (ownOnly ? plugin.tr("gui.market.own.orders") : plugin.tr("gui.market.own.all_buyers"))
                : (ownOnly ? plugin.tr("gui.market.own.listings") : plugin.tr("gui.market.own.all_sellers"));
        AdventureSupport.displayName(ownMeta, Component.text(ownLabel).color(NamedTextColor.GREEN));
        AdventureSupport.lore(ownMeta, List.of(Component.text(plugin.tr("gui.market.own.hint")).color(NamedTextColor.GRAY)));
        ownItem.setItemMeta(ownMeta);
        inventory.setItem(OWN_SLOT, ownItem);

        if (viewOptions.hasFilters()) {
            ItemStack clearItem = new ItemStack(Material.BARRIER);
            ItemMeta clearMeta = clearItem.getItemMeta();
            AdventureSupport.displayName(clearMeta, Component.text(plugin.tr("gui.market.filters.clear")).color(NamedTextColor.RED));
            AdventureSupport.lore(clearMeta, List.of(Component.text(plugin.tr("gui.market.filters.clear_hint")).color(NamedTextColor.GRAY)));
            clearItem.setItemMeta(clearMeta);
            inventory.setItem(CLEAR_SLOT, clearItem);
        }

        ItemStack pageInfo = new ItemStack(Material.PAPER, Math.max(1, currentPage + 1));
        ItemMeta pageMeta = pageInfo.getItemMeta();
        String modeName = viewOptions.displayMode() == DisplayMode.PURCHASING
                ? plugin.tr("gui.market.mode.purchasing")
                : plugin.tr("gui.market.mode.selling");
        AdventureSupport.displayName(pageMeta,
                Component.text(plugin.tr("gui.market.page_info", modeName, currentPage + 1, totalPages, filteredCount, totalOffers))
                        .color(NamedTextColor.WHITE));
        pageInfo.setItemMeta(pageMeta);
        inventory.setItem(PAGE_SLOT, pageInfo);

        ItemStack itemFilter = new ItemStack(viewOptions.itemQuery() == null ? Material.NAME_TAG : Material.WRITABLE_BOOK);
        ItemMeta itemMeta = itemFilter.getItemMeta();
        AdventureSupport.displayName(itemMeta, Component.text(viewOptions.itemQuery() == null
                        ? plugin.tr("gui.market.item_filter.search")
                        : plugin.tr("gui.market.item_filter.active"))
                .color(NamedTextColor.AQUA));
        List<Component> itemLore = new ArrayList<>();
        if (viewOptions.itemQuery() == null) {
            itemLore.add(Component.text(plugin.tr("gui.market.item_filter.hint")).color(NamedTextColor.GRAY));
        } else {
            itemLore.add(Component.text(viewOptions.itemQuery()).color(NamedTextColor.WHITE));
            itemLore.add(Component.text(plugin.tr("gui.common.left_change")).color(NamedTextColor.GRAY));
            itemLore.add(Component.text(plugin.tr("gui.common.right_clear")).color(NamedTextColor.GRAY));
        }
        AdventureSupport.lore(itemMeta, itemLore);
        itemFilter.setItemMeta(itemMeta);
        inventory.setItem(ITEM_FILTER_SLOT, itemFilter);

        String ownerLabel = viewOptions.displayMode() == DisplayMode.PURCHASING
                ? plugin.tr("common.buyer")
                : plugin.tr("common.seller");
        ItemStack sellerFilter = new ItemStack(viewOptions.sellerFilter() == null ? Material.COMPASS : Material.PLAYER_HEAD);
        ItemMeta sellerMeta = sellerFilter.getItemMeta();
        AdventureSupport.displayName(sellerMeta, Component.text(viewOptions.sellerFilter() == null
                        ? plugin.tr("gui.market.owner_filter.search", ownerLabel)
                        : plugin.tr("gui.market.owner_filter.active"))
                .color(NamedTextColor.AQUA));
        List<Component> sellerLore = new ArrayList<>();
        if (viewOptions.sellerFilter() == null) {
            sellerLore.add(Component.text(plugin.tr("gui.market.owner_filter.hint", ownerLabel)).color(NamedTextColor.GRAY));
        } else {
            sellerLore.add(Component.text(resolveSellerLabel()).color(NamedTextColor.WHITE));
            sellerLore.add(Component.text(plugin.tr("gui.common.left_change")).color(NamedTextColor.GRAY));
            sellerLore.add(Component.text(plugin.tr("gui.common.right_clear")).color(NamedTextColor.GRAY));
        }
        AdventureSupport.lore(sellerMeta, sellerLore);
        sellerFilter.setItemMeta(sellerMeta);
        inventory.setItem(SELLER_FILTER_SLOT, sellerFilter);

        DisplayMode nextMode = viewOptions.displayMode().toggle();
        ItemStack modeItem = new ItemStack(viewOptions.displayMode() == DisplayMode.PURCHASING
                ? Material.CHEST
                : Material.EMERALD);
        ItemMeta modeMeta = modeItem.getItemMeta();
        AdventureSupport.displayName(modeMeta, Component.text(nextMode == DisplayMode.PURCHASING
                        ? plugin.tr("gui.market.mode.purchasing")
                        : plugin.tr("gui.market.mode.selling"))
                .color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
        AdventureSupport.lore(modeMeta, List.of(Component.text(plugin.tr("gui.market.mode.hint")).color(NamedTextColor.GRAY)));
        modeItem.setItemMeta(modeMeta);
        inventory.setItem(MODE_SLOT, modeItem);

        if (currentPage < totalPages - 1) {
            ItemStack nextArrow = new ItemStack(Material.ARROW, 1);
            ItemMeta meta = nextArrow.getItemMeta();
            AdventureSupport.displayName(meta, Component.text(plugin.tr("gui.common.forward")).color(NamedTextColor.YELLOW));
            AdventureSupport.lore(meta, List.of(Component.text(plugin.tr("gui.common.page.next")).color(NamedTextColor.GRAY)));
            nextArrow.setItemMeta(meta);
            inventory.setItem(NEXT_SLOT, nextArrow);
        }
    }

    private String resolveSellerLabel() {
        if (viewOptions.sellerLabel() != null) {
            return viewOptions.sellerLabel();
        }
        return resolveSellerName(viewOptions.sellerFilter());
    }

    private String formatAge(long createdAt) {
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - createdAt);
        long minutes = elapsedMs / 60_000L;
        if (minutes < 1) {
            return plugin.tr("time.just_now");
        }
        if (minutes < 60) {
            return plugin.tr("time.minutes_ago", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return plugin.tr("time.hours_ago", hours);
        }
        long days = hours / 24;
        return plugin.tr("time.days_ago", days);
    }

    private void sendListingStats(List<MarketData.Listing> allListings,
                                  Map<Material, Long> totalPriceByItem,
                                  Map<Material, Integer> totalCountByItem) {
        long totalTurnover = 0L;
        for (long price : totalPriceByItem.values()) {
            totalTurnover += price;
        }

        long avgTurnover = 0L;
        for (Map.Entry<Material, Long> entry : totalPriceByItem.entrySet()) {
            int totalCount = totalCountByItem.getOrDefault(entry.getKey(), 0);
            if (totalCount > 0) {
                avgTurnover += entry.getValue() / totalCount;
            }
        }

        long capitalization = plugin.getMarketData().getTotalSales();
        plugin.sendActionBar(viewer, Component.text(plugin.tr(
                "gui.market.stats.selling", totalTurnover, avgTurnover, capitalization
        )).color(NamedTextColor.GOLD));
    }

    private void sendPurchaseStats(List<MarketData.PurchaseOrder> orders) {
        long reserved = 0L;
        int requested = 0;
        for (MarketData.PurchaseOrder order : orders) {
            reserved += order.totalPrice;
            requested += order.stack.getAmount();
        }
        plugin.sendActionBar(viewer, Component.text(plugin.tr(
                "gui.market.stats.purchasing", reserved, requested, orders.size()
        )).color(NamedTextColor.GOLD));
    }

    private String resolveSellerName(UUID seller) {
        if (seller == null) {
            return plugin.tr("common.unknown");
        }
        Player online = Bukkit.getPlayer(seller);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(seller).getName();
        return name != null ? name : seller.toString();
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Player getViewer() {
        return viewer;
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slotIndex = event.getRawSlot();
        if (slotIndex < 0 || slotIndex >= TOTAL_SLOTS) return;

        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (slotIndex >= NAV_ROW_START) {
            if (slotIndex == PREV_SLOT && currentPage > 0) {
                currentPage--;
                refresh();
            } else if (slotIndex == NEXT_SLOT && currentPage < totalPages - 1) {
                currentPage++;
                refresh();
            } else if (slotIndex == SORT_SLOT) {
                viewOptions = viewOptions.withSortMode(viewOptions.sortMode().next());
                currentPage = 0;
                refresh();
            } else if (slotIndex == OWN_SLOT) {
                viewOptions = viewOptions.toggleOwnListings(viewer);
                currentPage = 0;
                refresh();
            } else if (slotIndex == CLEAR_SLOT && viewOptions.hasFilters()) {
                viewOptions = viewOptions.clearFilters();
                currentPage = 0;
                refresh();
            } else if (slotIndex == ITEM_FILTER_SLOT) {
                if (event.isRightClick() && viewOptions.itemQuery() != null) {
                    viewOptions = viewOptions.withItemQuery(null);
                    currentPage = 0;
                    refresh();
                } else {
                    plugin.promptMarketFilter(player, EasyTradingPlugin.MarketFilterPromptType.ITEM_QUERY, viewOptions);
                }
            } else if (slotIndex == SELLER_FILTER_SLOT) {
                if (event.isRightClick() && viewOptions.sellerFilter() != null) {
                    viewOptions = viewOptions.withSellerFilter(null, null);
                    currentPage = 0;
                    refresh();
                } else {
                    plugin.promptMarketFilter(player, EasyTradingPlugin.MarketFilterPromptType.SELLER, viewOptions);
                }
            } else if (slotIndex == MODE_SLOT) {
                viewOptions = viewOptions.withDisplayMode(viewOptions.displayMode().toggle());
                currentPage = 0;
                refresh();
            }
            return;
        }

        int listingId = slotToListingId[slotIndex];
        if (listingId != -1) {
            handleOfferClick(player, listingId);
        }
    }

    private void handleOfferClick(Player player, int offerId) {
        if (viewOptions.displayMode() == DisplayMode.PURCHASING) {
            handlePurchaseOrderClick(player, offerId);
        } else {
            handleListingClick(player, offerId);
        }
    }

    private void handleListingClick(Player buyer, int listingId) {
        java.util.Optional<MarketData.Listing> listingOpt = plugin.getMarketData().getListing(listingId);
        if (listingOpt.isEmpty()) {
            plugin.sendMessage(buyer, plugin.trc("listing.already_sold", NamedTextColor.RED));
            return;
        }

        MarketData.Listing listing = listingOpt.get();
        if (listing.seller.equals(buyer.getUniqueId())) {
            plugin.handleBuyConfirm(buyer, true, listingId);
            plugin.refreshAllMarketGuis();
            return;
        }

        new ConfirmationGui(plugin, buyer, ConfirmationGui.Type.BUY, listingId,
                getItemName(listing.stack), listing.stack.getAmount(), listing.price, 0,
                resolveSellerName(listing.seller), 0).open();
    }

    private void handlePurchaseOrderClick(Player seller, int orderId) {
        java.util.Optional<MarketData.PurchaseOrder> orderOpt = plugin.getMarketData().getPurchaseOrder(orderId);
        if (orderOpt.isEmpty()) {
            plugin.sendMessage(seller, plugin.trc("purchase_order.missing", NamedTextColor.RED));
            return;
        }

        MarketData.PurchaseOrder order = orderOpt.get();
        if (order.buyer.equals(seller.getUniqueId())) {
            plugin.cancelPurchaseOrder(seller, orderId);
            plugin.refreshAllMarketGuis();
            return;
        }

        int available = plugin.countMatchingRequestedItems(seller, order.stack);
        if (available < order.stack.getAmount()) {
            plugin.sendMessage(seller, plugin.trc("purchase_order.need_matching_items_fulfill",
                    NamedTextColor.RED, order.stack.getAmount()));
            return;
        }

        int accepted = order.stack.getAmount();
        long total = order.totalPrice;
        new ConfirmationGui(plugin, seller, ConfirmationGui.Type.SELL_TO_ORDER, order.id,
                getItemName(order.stack), accepted, total, total,
                resolveSellerName(order.buyer), 0).open();
    }

    public void handleClose() {
        OPEN_GUIS.remove(this);
    }

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
        EasyTradingPlugin plugin = EasyTradingPlugin.getInstance();
        if (plugin != null && plugin.getLocalization() != null) {
            return plugin.getLocalization().itemName(stack);
        }
        if (stack == null) return "Unknown";
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return org.bukkit.ChatColor.stripColor(meta.getDisplayName());
        }

        String name = stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
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



