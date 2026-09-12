package com.easytrading.paper.data;

import com.google.gson.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EconomyData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Path file;

    public EconomyData(Path dataFolder) {
        this.file = dataFolder.resolve("economy.json");
        load();
    }

    public synchronized long get(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    public synchronized void set(UUID player, long amount) {
        balances.put(player, amount);
    }

    public synchronized void add(UUID player, long delta) {
        set(player, get(player) + delta);
    }

    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            balances.clear();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    balances.put(uuid, entry.getValue().getAsLong());
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Long> e : balances.entrySet()) {
                root.addProperty(e.getKey().toString(), e.getValue());
            }
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
