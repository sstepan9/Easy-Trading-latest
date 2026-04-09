package com.easytrading.paper.data;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Market version tracking per-player for new-item notifications.
 */
public class MarketNotifyData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private long marketVersion = 0L;
    private final Map<UUID, Long> lastSeen = new HashMap<>();
    private final Path file;

    public MarketNotifyData(Path dataFolder) {
        this.file = dataFolder.resolve("market-notify.json");
        load();
    }

    public long getVersion() { return marketVersion; }

    public Long getLastSeen(UUID player) { return lastSeen.get(player); }

    public void markSeen(UUID player, long version) {
        lastSeen.put(player, version);
    }

    public long bump() {
        marketVersion++;
        return marketVersion;
    }

    public void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            marketVersion = root.has("version") ? root.get("version").getAsLong() : 0L;
            lastSeen.clear();
            if (root.has("lastSeen")) {
                JsonObject map = root.getAsJsonObject("lastSeen");
                for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        lastSeen.put(uuid, entry.getValue().getAsLong());
                    } catch (Exception ignored) {}
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
            root.addProperty("version", marketVersion);
            JsonObject map = new JsonObject();
            for (Map.Entry<UUID, Long> e : lastSeen.entrySet()) {
                map.addProperty(e.getKey().toString(), e.getValue());
            }
            root.add("lastSeen", map);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
