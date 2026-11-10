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

/** Persisted per-player display and trade-request preferences. */
public final class HudPreferencesData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<UUID, Preferences> preferences = new HashMap<>();

    public HudPreferencesData(Path dataFolder) {
        file = dataFolder.resolve("hud-preferences.json");
        load();
    }

    public synchronized Optional<String> getHudMode(UUID playerId) {
        return preference(playerId).map(Preferences::hudMode);
    }

    public synchronized Optional<String> getNumberFormat(UUID playerId) {
        return preference(playerId).map(Preferences::numberFormat);
    }

    public synchronized Optional<String> getTradeRequestMode(UUID playerId) {
        return preference(playerId).map(Preferences::tradeRequestMode);
    }

    public synchronized void setHudMode(UUID playerId, String mode) {
        update(playerId, current -> new Preferences(mode, current.numberFormat(), current.tradeRequestMode()));
    }

    public synchronized void setNumberFormat(UUID playerId, String format) {
        update(playerId, current -> new Preferences(current.hudMode(), format, current.tradeRequestMode()));
    }

    public synchronized void setTradeRequestMode(UUID playerId, String mode) {
        update(playerId, current -> new Preferences(current.hudMode(), current.numberFormat(), mode));
    }

    private Optional<Preferences> preference(UUID playerId) {
        return Optional.ofNullable(preferences.get(playerId));
    }

    private void update(UUID playerId, java.util.function.UnaryOperator<Preferences> update) {
        if (playerId == null) return;
        preferences.put(playerId, update.apply(preferences.getOrDefault(playerId, Preferences.EMPTY)));
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    UUID playerId = UUID.fromString(entry.getKey());
                    // Accept the original string format as a HUD-only preference.
                    if (entry.getValue().isJsonPrimitive()) {
                        preferences.put(playerId, new Preferences(entry.getValue().getAsString(), null, null));
                        continue;
                    }
                    JsonObject value = entry.getValue().getAsJsonObject();
                    preferences.put(playerId, new Preferences(
                            value.has("hudMode") ? value.get("hudMode").getAsString() : null,
                            value.has("numberFormat") ? value.get("numberFormat").getAsString() : null,
                            value.has("tradeRequestMode") ? value.get("tradeRequestMode").getAsString() : null));
                } catch (Exception ignored) {
                    // A malformed preference must not block the remaining players.
                }
            }
        } catch (Exception ignored) {
            // Player preferences are non-critical and may safely return to defaults.
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Preferences> entry : preferences.entrySet()) {
                JsonObject value = new JsonObject();
                Preferences preference = entry.getValue();
                if (preference.hudMode() != null) value.addProperty("hudMode", preference.hudMode());
                if (preference.numberFormat() != null) value.addProperty("numberFormat", preference.numberFormat());
                if (preference.tradeRequestMode() != null) value.addProperty("tradeRequestMode", preference.tradeRequestMode());
                root.add(entry.getKey().toString(), value);
            }
            DataFileUtil.writeStringAtomic(file, GSON.toJson(root));
        } catch (IOException ignored) {
            // A failed preference write must never interrupt gameplay.
        }
    }

    private record Preferences(String hudMode, String numberFormat, String tradeRequestMode) {
        private static final Preferences EMPTY = new Preferences(null, null, null);
    }
}
