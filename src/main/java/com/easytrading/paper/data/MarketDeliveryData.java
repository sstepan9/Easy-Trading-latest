package com.easytrading.paper.data;

import com.easytrading.paper.util.Items;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pending market deliveries for purchase orders.
 */
public class MarketDeliveryData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, List<ItemStack>> deliveries = new HashMap<>();
    private final Path file;

    public MarketDeliveryData(Path dataFolder) {
        this.file = dataFolder.resolve("market-deliveries.json");
        load();
    }

    public synchronized void add(UUID playerId, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        List<ItemStack> items = deliveries.computeIfAbsent(playerId, ignored -> new ArrayList<>());
        for (ItemStack split : splitStack(stack)) {
            items.add(split);
        }
    }

    public synchronized List<ItemStack> get(UUID playerId) {
        List<ItemStack> items = deliveries.get(playerId);
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copy.add(item.clone());
        }
        return copy;
    }

    public synchronized void set(UUID playerId, List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            deliveries.remove(playerId);
            return;
        }
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            copy.add(item.clone());
        }
        if (copy.isEmpty()) {
            deliveries.remove(playerId);
        } else {
            deliveries.put(playerId, copy);
        }
    }

    public synchronized boolean has(UUID playerId) {
        List<ItemStack> items = deliveries.get(playerId);
        return items != null && !items.isEmpty();
    }

    public void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            deliveries.clear();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                JsonArray array = entry.getValue().getAsJsonArray();
                List<ItemStack> items = new ArrayList<>();
                for (JsonElement element : array) {
                    JsonObject obj = element.getAsJsonObject();
                    if (!obj.has("itemBytes")) {
                        continue;
                    }
                    try {
                        byte[] bytes = Base64.getDecoder().decode(obj.get("itemBytes").getAsString());
                        items.add(Items.deserialize(bytes));
                    } catch (Exception ignored) {
                        // Skip corrupted legacy delivery entries instead of aborting the whole file load.
                    }
                }
                if (!items.isEmpty()) {
                    deliveries.put(playerId, items);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, List<ItemStack>> entry : deliveries.entrySet()) {
                JsonArray array = new JsonArray();
                for (ItemStack item : entry.getValue()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("itemBytes", Base64.getEncoder().encodeToString(Items.serialize(item)));
                    array.add(obj);
                }
                root.add(entry.getKey().toString(), array);
            }
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static List<ItemStack> splitStack(ItemStack stack) {
        List<ItemStack> split = new ArrayList<>();
        int remaining = stack.getAmount();
        int maxStack = Math.max(1, stack.getMaxStackSize());
        while (remaining > 0) {
            int nextAmount = Math.min(maxStack, remaining);
            ItemStack next = stack.clone();
            next.setAmount(nextAmount);
            split.add(next);
            remaining -= nextAmount;
        }
        return split;
    }
}


