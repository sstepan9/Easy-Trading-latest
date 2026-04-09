package com.easytrading.paper.data;

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

        public Listing(int id, UUID seller, ItemStack stack, long price, long createdAt) {
            this.id = id;
            this.seller = seller;
            this.stack = stack;
            this.price = price;
            this.createdAt = createdAt;
        }
    }

    private final Map<Integer, Listing> listings = new LinkedHashMap<>();
    private int nextId = 1;
    private long totalSales = 0L;
    private final Path file;

    public MarketData(Path dataFolder) {
        this.file = dataFolder.resolve("market.json");
        load();
    }

    public synchronized Listing add(UUID seller, ItemStack stack, long price, long createdAt) {
        int id = nextId++;
        Listing l = new Listing(id, seller, stack.clone(), price, createdAt);
        listings.put(id, l);
        return l;
    }

    public synchronized Optional<Listing> getListing(int id) {
        return Optional.ofNullable(listings.get(id));
    }

    public synchronized Optional<Listing> remove(int id) {
        Listing l = listings.remove(id);
        return Optional.ofNullable(l);
    }

    public synchronized void putBack(Listing listing) {
        listings.put(listing.id, listing);
        if (listing.id >= nextId) {
            nextId = listing.id + 1;
        }
    }

    public synchronized List<Listing> getAllListingsSorted() {
        List<Listing> out = new ArrayList<>(listings.values());
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

    public synchronized void addSale(long amount) {
        this.totalSales += amount;
    }

    public synchronized long getTotalSales() {
        return totalSales;
    }

    @SuppressWarnings("deprecation")
    public void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            nextId = root.has("nextId") ? root.get("nextId").getAsInt() : 1;
            totalSales = root.has("totalSales") ? root.get("totalSales").getAsLong() : 0L;
            listings.clear();
            if (root.has("listings")) {
                JsonArray arr = root.getAsJsonArray("listings");
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    int id = obj.get("id").getAsInt();
                    UUID seller = UUID.fromString(obj.get("seller").getAsString());
                    long price = obj.get("price").getAsLong();
                    long createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong() : 0L;

                    ItemStack stack;
                    if (obj.has("itemBytes")) {
                        // base64 serialized ItemStack
                        byte[] bytes = Base64.getDecoder().decode(obj.get("itemBytes").getAsString());
                        stack = ItemStack.deserializeBytes(bytes);
                    } else {
                        // Legacy: material + count
                        String matName = obj.has("material") ? obj.get("material").getAsString() : "STONE";
                        Material mat = Material.matchMaterial(matName);
                        if (mat == null) mat = Material.STONE;
                        int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
                        stack = new ItemStack(mat, count);
                    }

                    Listing l = new Listing(id, seller, stack, price, createdAt);
                    listings.put(id, l);
                }
            }
            if (nextId <= 0) {
                int max = 0;
                for (int id : listings.keySet()) max = Math.max(max, id);
                nextId = max + 1;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("nextId", nextId);
            root.addProperty("totalSales", totalSales);
            JsonArray arr = new JsonArray();
            for (Listing l : listings.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", l.id);
                obj.addProperty("seller", l.seller.toString());
                obj.addProperty("price", l.price);
                obj.addProperty("createdAt", l.createdAt);
                // Serialize full ItemStack as base64
                byte[] bytes = l.stack.serializeAsBytes();
                obj.addProperty("itemBytes", Base64.getEncoder().encodeToString(bytes));
                // Also store material for readability
                obj.addProperty("material", l.stack.getType().name());
                obj.addProperty("count", l.stack.getAmount());
                arr.add(obj);
            }
            root.add("listings", arr);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
