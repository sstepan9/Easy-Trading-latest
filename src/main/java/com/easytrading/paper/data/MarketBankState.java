package com.easytrading.paper.data;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.*;

/**
 * Daily sell/buy limits tracking per player per resource.
 */
public class MarketBankState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String date = "";
    private final Map<UUID, Map<String, Integer>> soldToday = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> boughtToday = new HashMap<>();
    private final Path file;

    public MarketBankState(Path dataFolder) {
        this.file = dataFolder.resolve("bank-state.json");
        load();
    }

    public synchronized void ensureToday() {
        String today = LocalDate.now().toString();
        if (!today.equals(date)) {
            date = today;
            soldToday.clear();
            boughtToday.clear();
        }
    }

    public synchronized void resetToday() {
        date = LocalDate.now().toString();
        soldToday.clear();
        boughtToday.clear();
    }

    public synchronized int getRemaining(UUID playerId, String itemId, int limit) {
        Map<String, Integer> items = soldToday.get(playerId);
        int sold = items == null ? 0 : items.getOrDefault(itemId, 0);
        return Math.max(0, limit - sold);
    }

    public synchronized void addSold(UUID playerId, String itemId, int count) {
        soldToday.computeIfAbsent(playerId, k -> new HashMap<>())
                .merge(itemId, count, Integer::sum);
    }

    public synchronized int getRemainingBuy(UUID playerId, String itemId, int limit) {
        Map<String, Integer> items = boughtToday.get(playerId);
        int bought = items == null ? 0 : items.getOrDefault(itemId, 0);
        return Math.max(0, limit - bought);
    }

    public synchronized void addBought(UUID playerId, String itemId, int count) {
        boughtToday.computeIfAbsent(playerId, k -> new HashMap<>())
                .merge(itemId, count, Integer::sum);
    }

    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            date = root.has("date") ? root.get("date").getAsString() : "";
            readNested(root.has("sold") ? root.getAsJsonObject("sold") : new JsonObject(), soldToday);
            readNested(root.has("bought") ? root.getAsJsonObject("bought") : new JsonObject(), boughtToday);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("date", date == null ? "" : date);
            root.add("sold", writeNested(soldToday));
            root.add("bought", writeNested(boughtToday));
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void readNested(JsonObject obj, Map<UUID, Map<String, Integer>> out) {
        out.clear();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                JsonObject items = entry.getValue().getAsJsonObject();
                Map<String, Integer> map = new HashMap<>();
                for (Map.Entry<String, JsonElement> item : items.entrySet()) {
                    map.put(item.getKey(), item.getValue().getAsInt());
                }
                out.put(uuid, map);
            } catch (Exception ignored) {}
        }
    }

    private JsonObject writeNested(Map<UUID, Map<String, Integer>> map) {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, Map<String, Integer>> e : map.entrySet()) {
            JsonObject items = new JsonObject();
            for (Map.Entry<String, Integer> item : e.getValue().entrySet()) {
                items.addProperty(item.getKey(), item.getValue());
            }
            root.add(e.getKey().toString(), items);
        }
        return root;
    }
}
