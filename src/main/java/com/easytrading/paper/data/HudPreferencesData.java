package com.easytrading.paper.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persisted per-player balance display choices. */
public final class HudPreferencesData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<UUID, String> modes = new HashMap<>();

    public HudPreferencesData(Path dataFolder) {
        file = dataFolder.resolve("hud-preferences.json");
        load();
    }

    public synchronized Optional<String> getMode(UUID playerId) {
        return Optional.ofNullable(modes.get(playerId));
    }

    public synchronized void setMode(UUID playerId, String mode) {
        if (playerId == null || mode == null || mode.isBlank()) return;
        modes.put(playerId, mode);
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    UUID playerId = UUID.fromString(entry.getKey());
                    String mode = entry.getValue().getAsString();
                    if (!mode.isBlank()) modes.put(playerId, mode);
                } catch (Exception ignored) {
                    // A malformed preference must not block the remaining players.
                }
            }
        } catch (Exception ignored) {
            // HUD preferences are non-critical and may safely return to defaults.
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, String> entry : modes.entrySet()) {
                root.addProperty(entry.getKey().toString(), entry.getValue());
            }
            DataFileUtil.writeStringAtomic(file, GSON.toJson(root));
        } catch (IOException ignored) {
            // A failed preference write must never interrupt gameplay.
        }
    }
}
