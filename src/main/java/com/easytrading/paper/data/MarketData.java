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
        int id = nextId++;
        Listing l = new Listing(id, seller, stack.clone(), price, createdAt, promoted);
        listings.put(id, l);
        return l;
    }

    public synchronized Optional<Listing> getListing(int id) {
        return Optional.ofNullable(listings.get(id));
    }

    public synchronized PurchaseOrder addPurchaseOrder(UUID buyer, ItemStack stack, long totalPrice, long createdAt) {
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
        listings.put(listing.id, listing);
        if (listing.id >= nextId) {
            nextId = listing.id + 1;
        }
    }

    public synchronized void putBackPurchaseOrder(PurchaseOrder order) {
        purchaseOrders.put(order.id, order);
        if (order.id >= nextPurchaseOrderId) {
            nextPurchaseOrderId = order.id + 1;
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
        if (existing == null) {
            return Optional.empty();
        }
        Listing updated = new Listing(existing.id, existing.seller, existing.stack.clone(), newPrice, relistedAt,
                existing.promoted);
        listings.put(id, updated);
        return Optional.of(updated);
    }

    public synchronized void addSale(long amount) {
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
            totalSales = root.has("totalSales") ? root.get("totalSales").getAsLong() : 0L;
            listings.clear();
            purchaseOrders.clear();
            if (root.has("listings")) {
                JsonArray arr = root.getAsJsonArray("listings");
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    int id = obj.get("id").getAsInt();
                    UUID seller = UUID.fromString(obj.get("seller").getAsString());
                    long price = obj.get("price").getAsLong();
                    long createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong() : 0L;
                    boolean promoted = obj.has("promoted") && obj.get("promoted").getAsBoolean();

                    ItemStack stack = readItemStack(obj);

                    Listing l = new Listing(id, seller, stack, price, createdAt, promoted);
                    listings.put(id, l);
                }
            }
            if (root.has("purchaseOrders")) {
                JsonArray arr = root.getAsJsonArray("purchaseOrders");
                for (JsonElement el : arr) {
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
                        totalPrice = obj.get("pricePerItem").getAsLong() * stack.getAmount();
                    } else {
                        totalPrice = 0L;
                    }

                    PurchaseOrder order = new PurchaseOrder(id, buyer, stack, totalPrice, createdAt);
                    purchaseOrders.put(id, order);
                }
            }
            if (nextId <= 0) {
                int max = 0;
                for (int id : listings.keySet()) max = Math.max(max, id);
                nextId = max + 1;
            }
            if (nextPurchaseOrderId <= 0) {
                int max = 0;
                for (int id : purchaseOrders.keySet()) max = Math.max(max, id);
                nextPurchaseOrderId = max + 1;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ItemStack readItemStack(JsonObject obj) {
        if (obj.has("itemBytes")) {
            try {
                byte[] bytes = Base64.getDecoder().decode(obj.get("itemBytes").getAsString());
                return Items.deserialize(bytes);
            } catch (Exception ignored) {
                // Fall back to legacy material/count fields if itemBytes came from an older format.
            }
        }

        String matName = obj.has("material") ? obj.get("material").getAsString() : "STONE";
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            mat = Material.STONE;
        }
        int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
        return new ItemStack(mat, Math.max(1, count));
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
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

