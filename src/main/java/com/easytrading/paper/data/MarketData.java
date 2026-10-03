package com.easytrading.paper.data;

import com.easytrading.paper.util.Items;
import com.google.gson.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Market listings – P2P trading.
 * Saved as JSON in plugins/EasyTrading/market.json.
 */
public class MarketData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class Listing {
        public final int id;
        public final UUID seller;
        public final ItemStack stack;
        public final long price;
        public final long createdAt;
        public final boolean promoted;

        public Listing(int id, UUID seller, ItemStack stack, long price, long createdAt) {
            this(id, seller, stack, price, createdAt, false);
        }

        public Listing(int id, UUID seller, ItemStack stack, long price, long createdAt, boolean promoted) {
            this.id = id;
            this.seller = seller;
            this.stack = stack;
            this.price = price;
            this.createdAt = createdAt;
            this.promoted = promoted;
        }
    }

    public static class PurchaseOrder {
        public final int id;
        public final UUID buyer;
        public final ItemStack stack;
        public final long totalPrice;
        public final long createdAt;

        public PurchaseOrder(int id, UUID buyer, ItemStack stack, long totalPrice, long createdAt) {
            this.id = id;
            this.buyer = buyer;
            this.stack = stack;
            this.totalPrice = totalPrice;
            this.createdAt = createdAt;
        }
    }

    private final Map<Integer, Listing> listings = new LinkedHashMap<>();
    private final Map<Integer, PurchaseOrder> purchaseOrders = new LinkedHashMap<>();
    private int nextId = 1;
    private int nextPurchaseOrderId = 1;
    private long totalSales = 0L;
    private final Path file;

    public MarketData(Path dataFolder) {
        this.file = dataFolder.resolve("market.json");
        load();
    }

    public synchronized Listing add(UUID seller, ItemStack stack, long price, long createdAt) {
        return add(seller, stack, price, createdAt, false);
    }

    public synchronized Listing add(UUID seller, ItemStack stack, long price, long createdAt, boolean promoted) {
        if (seller == null || !isValidListingStack(stack)) {
            throw new IllegalArgumentException("Listing seller/item is invalid");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Listing price must be positive");
        }
        if (nextId <= 0 || nextId == Integer.MAX_VALUE) {
            throw new IllegalStateException("Listing ID space exhausted");
        }
        int id = nextId++;
        Listing l = new Listing(id, seller, stack.clone(), price, createdAt, promoted);
        listings.put(id, l);
        return l;
    }

    public synchronized Optional<Listing> getListing(int id) {
        return Optional.ofNullable(listings.get(id));
    }

    public synchronized PurchaseOrder addPurchaseOrder(UUID buyer, ItemStack stack, long totalPrice, long createdAt) {
        if (buyer == null || !isValidStack(stack)) {
            throw new IllegalArgumentException("Purchase order buyer/item is invalid");
        }
        if (totalPrice <= 0) {
            throw new IllegalArgumentException("Purchase order price must be positive");
        }
        if (nextPurchaseOrderId <= 0 || nextPurchaseOrderId == Integer.MAX_VALUE) {
            throw new IllegalStateException("Purchase order ID space exhausted");
        }
        int id = nextPurchaseOrderId++;
        PurchaseOrder order = new PurchaseOrder(id, buyer, stack.clone(), totalPrice, createdAt);
        purchaseOrders.put(id, order);
        return order;
    }

    public synchronized Optional<PurchaseOrder> getPurchaseOrder(int id) {
        return Optional.ofNullable(purchaseOrders.get(id));
    }

    public synchronized Optional<Listing> remove(int id) {
        Listing l = listings.remove(id);
        return Optional.ofNullable(l);
    }

    public synchronized Optional<PurchaseOrder> removePurchaseOrder(int id) {
        PurchaseOrder order = purchaseOrders.remove(id);
        return Optional.ofNullable(order);
    }

    public synchronized void putBack(Listing listing) {
        if (!isValidListing(listing)) {
            return;
        }
        listings.put(listing.id, listing);
        if (listing.id >= nextId) {
            nextId = listing.id == Integer.MAX_VALUE ? Integer.MAX_VALUE : listing.id + 1;
        }
    }

    public synchronized void putBackPurchaseOrder(PurchaseOrder order) {
        if (!isValidPurchaseOrder(order)) {
            return;
        }
        purchaseOrders.put(order.id, order);
        if (order.id >= nextPurchaseOrderId) {
            nextPurchaseOrderId = order.id == Integer.MAX_VALUE ? Integer.MAX_VALUE : order.id + 1;
        }
    }

    public synchronized List<Listing> getAllListingsSorted() {
        List<Listing> out = new ArrayList<>(listings.values());
        // Promoted listings always form the first group. MarketGui applies the
        // selected price/date ordering inside each of the two groups.
        out.sort(Comparator.comparing((Listing listing) -> !listing.promoted)
                .thenComparingInt(listing -> listing.id));
        return out;
    }

    public synchronized List<PurchaseOrder> getAllPurchaseOrdersSorted() {
        List<PurchaseOrder> out = new ArrayList<>(purchaseOrders.values());
        out.sort(Comparator.comparingInt(a -> a.id));
        return out;
    }

    public synchronized int countBySeller(UUID seller) {
        int count = 0;
        for (Listing listing : listings.values()) {
            if (listing.seller.equals(seller)) count++;
        }
        return count;
    }

    public synchronized int countPromotedBySeller(UUID seller) {
        int count = 0;
        for (Listing listing : listings.values()) {
            if (listing.promoted && listing.seller.equals(seller)) {
                count++;
            }
        }
        return count;
    }

    public synchronized int countPurchaseOrdersByBuyer(UUID buyer) {
        int count = 0;
        for (PurchaseOrder order : purchaseOrders.values()) {
            if (order.buyer.equals(buyer)) count++;
        }
        return count;
    }

    public synchronized int countOffersByOwner(UUID owner) {
        return countBySeller(owner) + countPurchaseOrdersByBuyer(owner);
    }

    public synchronized List<Listing> getListingsBySeller(UUID seller) {
        List<Listing> result = new ArrayList<>();
        for (Listing listing : listings.values()) {
            if (listing.seller.equals(seller)) {
                result.add(listing);
            }
        }
        result.sort(Comparator.comparingLong((Listing listing) -> listing.createdAt)
                .thenComparingInt(listing -> listing.id)
                .reversed());
        return result;
    }

    public synchronized List<PurchaseOrder> getPurchaseOrdersByBuyer(UUID buyer) {
        List<PurchaseOrder> result = new ArrayList<>();
        for (PurchaseOrder order : purchaseOrders.values()) {
            if (order.buyer.equals(buyer)) {
                result.add(order);
            }
        }
        result.sort(Comparator.comparingLong((PurchaseOrder order) -> order.createdAt)
                .thenComparingInt(order -> order.id)
                .reversed());
        return result;
    }

    public synchronized List<Listing> removeBySeller(UUID seller) {
        List<Listing> removed = new ArrayList<>();
        Iterator<Map.Entry<Integer, Listing>> iterator = listings.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Listing> entry = iterator.next();
            if (entry.getValue().seller.equals(seller)) {
                removed.add(entry.getValue());
                iterator.remove();
            }
        }
        removed.sort(Comparator.comparingLong((Listing listing) -> listing.createdAt)
                .thenComparingInt(listing -> listing.id)
                .reversed());
        return removed;
    }

    public synchronized Optional<Listing> relist(int id, long newPrice, long relistedAt) {
        Listing existing = listings.get(id);
        if (existing == null || newPrice <= 0 || !isValidListingStack(existing.stack)) {
            return Optional.empty();
        }
        Listing updated = new Listing(existing.id, existing.seller, existing.stack.clone(), newPrice, relistedAt,
                existing.promoted);
        listings.put(id, updated);
        return Optional.of(updated);
    }

    public synchronized void addSale(long amount) {
        if (amount < 0 || this.totalSales > Long.MAX_VALUE - amount) {
            return;
        }
        this.totalSales += amount;
    }

    public synchronized long getTotalSales() {
        return totalSales;
    }

    @SuppressWarnings("deprecation")
    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            nextId = root.has("nextId") ? root.get("nextId").getAsInt() : 1;
            nextPurchaseOrderId = root.has("nextPurchaseOrderId") ? root.get("nextPurchaseOrderId").getAsInt() : 1;
            totalSales = root.has("totalSales") ? Math.max(0L, root.get("totalSales").getAsLong()) : 0L;
            listings.clear();
            purchaseOrders.clear();
            if (root.has("listings")) {
                JsonArray arr = root.getAsJsonArray("listings");
                for (JsonElement el : arr) {
                    try {
                        JsonObject obj = el.getAsJsonObject();
                        int id = obj.get("id").getAsInt();
                        UUID seller = UUID.fromString(obj.get("seller").getAsString());
                        long price = obj.get("price").getAsLong();
                        long createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong() : 0L;
                        boolean promoted = obj.has("promoted") && obj.get("promoted").getAsBoolean();
                        ItemStack stack = readItemStack(obj);
                        if (id <= 0 || price <= 0 || !isValidListingStack(stack)) continue;
                        Listing l = new Listing(id, seller, stack, price, createdAt, promoted);
                        listings.put(id, l);
                    } catch (Exception ignored) {
                        // One corrupt entry must not prevent the remaining
                        // listings from loading.
                    }
                }
            }
            if (root.has("purchaseOrders")) {
                JsonArray arr = root.getAsJsonArray("purchaseOrders");
                for (JsonElement el : arr) {
                    try {
                        JsonObject obj = el.getAsJsonObject();
                        int id = obj.get("id").getAsInt();
                        UUID buyer = UUID.fromString(obj.get("buyer").getAsString());
                        long createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong() : 0L;
                        ItemStack stack = readItemStack(obj);
                        long totalPrice;
                        if (obj.has("totalPrice")) {
                            totalPrice = obj.get("totalPrice").getAsLong();
                        } else if (obj.has("reservedTotal")) {
                            totalPrice = obj.get("reservedTotal").getAsLong();
                        } else if (obj.has("pricePerItem")) {
                            totalPrice = Math.multiplyExact(obj.get("pricePerItem").getAsLong(), stack.getAmount());
                        } else {
                            totalPrice = 0L;
                        }
                        if (id <= 0 || totalPrice <= 0 || !isValidStack(stack)) continue;
                        PurchaseOrder order = new PurchaseOrder(id, buyer, stack, totalPrice, createdAt);
                        purchaseOrders.put(id, order);
                    } catch (Exception ignored) {
                        // Skip only the malformed order and continue loading.
                    }
                }
            }
            int maxListingId = 0;
            for (int id : listings.keySet()) maxListingId = Math.max(maxListingId, id);
            if (nextId <= maxListingId || nextId <= 0) {
                nextId = maxListingId == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxListingId + 1;
            }
            int maxOrderId = 0;
            for (int id : purchaseOrders.keySet()) maxOrderId = Math.max(maxOrderId, id);
            if (nextPurchaseOrderId <= maxOrderId || nextPurchaseOrderId <= 0) {
                nextPurchaseOrderId = maxOrderId == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxOrderId + 1;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ItemStack readItemStack(JsonObject obj) {
        ItemStack decoded = null;
        if (obj.has("itemBytes")) {
            try {
                byte[] bytes = Base64.getDecoder().decode(obj.get("itemBytes").getAsString());
                decoded = Items.deserialize(bytes);
            } catch (Exception ignored) {
                // Fall back to legacy material/count fields if itemBytes came from an older format.
            }
        }

        if (isValidStack(decoded)) {
            return decoded;
        }

        String matName = obj.has("material") ? obj.get("material").getAsString() : "STONE";
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            mat = Material.STONE;
        }
        int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
        if (count <= 0) {
            throw new IllegalArgumentException("Invalid item count");
        }
        return new ItemStack(mat, count);
    }

    private static boolean isValidStack(ItemStack stack) {
        return stack != null && !Items.isEmpty(stack);
    }

    private static boolean isValidListingStack(ItemStack stack) {
        return isValidStack(stack) && stack.getAmount() <= stack.getMaxStackSize();
    }

    private static boolean isValidListing(Listing listing) {
        return listing != null && listing.id > 0 && listing.seller != null
                && listing.price > 0 && isValidListingStack(listing.stack);
    }

    private static boolean isValidPurchaseOrder(PurchaseOrder order) {
        return order != null && order.id > 0 && order.buyer != null
                && order.totalPrice > 0 && isValidStack(order.stack);
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("nextId", nextId);
            root.addProperty("nextPurchaseOrderId", nextPurchaseOrderId);
            root.addProperty("totalSales", totalSales);
            JsonArray arr = new JsonArray();
            for (Listing l : listings.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", l.id);
                obj.addProperty("seller", l.seller.toString());
                obj.addProperty("price", l.price);
                obj.addProperty("createdAt", l.createdAt);
                obj.addProperty("promoted", l.promoted);
                // Serialize full ItemStack as base64
                byte[] bytes = Items.serialize(l.stack);
                obj.addProperty("itemBytes", Base64.getEncoder().encodeToString(bytes));
                // Also store material for readability
                obj.addProperty("material", l.stack.getType().name());
                obj.addProperty("count", l.stack.getAmount());
                arr.add(obj);
            }
            root.add("listings", arr);
            JsonArray purchaseArr = new JsonArray();
            for (PurchaseOrder order : purchaseOrders.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", order.id);
                obj.addProperty("buyer", order.buyer.toString());
                obj.addProperty("totalPrice", order.totalPrice);
                obj.addProperty("createdAt", order.createdAt);
                byte[] bytes = Items.serialize(order.stack);
                obj.addProperty("itemBytes", Base64.getEncoder().encodeToString(bytes));
                obj.addProperty("material", order.stack.getType().name());
                obj.addProperty("count", order.stack.getAmount());
                purchaseArr.add(obj);
            }
            root.add("purchaseOrders", purchaseArr);
            DataFileUtil.writeStringAtomic(file, GSON.toJson(root));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

