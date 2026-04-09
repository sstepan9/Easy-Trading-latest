package com.easytrading.paper.data;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Market config — listing fees, caps, price ranges.
 * Config file: plugins/EasyTrading/market-config.json
 */
public class MarketConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private int freeListingSlots = 2;
    private int softListingCap = 8;
    private int hardListingCap = 28;
    private int baseFeePercent = 4;
    private int progressiveFeePercent = 2;
    private long feeMin = 1;
    private long feeMax = 250_000;
    private long minPrice = 10;
    private long maxPrice = 5_000_000;
    private long lastModified = -1L;

    private final Path configPath;

    public MarketConfig(Path dataFolder) {
        this.configPath = dataFolder.resolve("market-config.json");
    }

    public int getFreeListingSlots() { return freeListingSlots; }
    public int getSoftListingCap() { return softListingCap; }
    public int getHardListingCap() { return hardListingCap; }
    public int getBaseFeePercent() { return baseFeePercent; }
    public int getProgressiveFeePercent() { return progressiveFeePercent; }
    public long getFeeMin() { return feeMin; }
    public long getFeeMax() { return feeMax; }
    public long getMinPrice() { return minPrice; }
    public long getMaxPrice() { return maxPrice; }

    public void ensureLoaded() {
        reloadIfChanged();
    }

    public void reload() {
        load(true);
    }

    private void reloadIfChanged() {
        try {
            if (Files.exists(configPath)) {
                long modified = Files.getLastModifiedTime(configPath).toMillis();
                if (modified != lastModified) {
                    load(false);
                }
            } else {
                load(true);
            }
        } catch (IOException ignored) {}
    }

    private void load(boolean createIfMissing) {
        if (!Files.exists(configPath)) {
            if (createIfMissing) writeDefault();
        }
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                if (root.has("free_listing_slots")) freeListingSlots = Math.max(0, root.get("free_listing_slots").getAsInt());
                if (root.has("soft_listing_cap")) softListingCap = Math.max(0, root.get("soft_listing_cap").getAsInt());
                if (root.has("hard_listing_cap")) hardListingCap = Math.max(softListingCap, root.get("hard_listing_cap").getAsInt());
                if (root.has("base_fee_percent")) baseFeePercent = Math.max(0, Math.min(100, root.get("base_fee_percent").getAsInt()));
                if (root.has("progressive_fee_percent")) progressiveFeePercent = Math.max(0, Math.min(100, root.get("progressive_fee_percent").getAsInt()));
                if (root.has("fee_min")) feeMin = Math.max(0, root.get("fee_min").getAsLong());
                if (root.has("fee_max")) feeMax = Math.max(feeMin, root.get("fee_max").getAsLong());
                if (root.has("min_price")) minPrice = Math.max(1, root.get("min_price").getAsLong());
                if (root.has("max_price")) maxPrice = Math.max(minPrice, root.get("max_price").getAsLong());

                // Backward compatibility
                if (root.has("fee_threshold") && !root.has("soft_listing_cap")) {
                    softListingCap = Math.max(0, root.get("fee_threshold").getAsInt());
                    hardListingCap = Math.max(softListingCap, softListingCap * 3);
                }
                if (root.has("fee_percent") && !root.has("progressive_fee_percent")) {
                    progressiveFeePercent = Math.max(0, Math.min(100, root.get("fee_percent").getAsInt()));
                }

                softListingCap = Math.max(freeListingSlots, softListingCap);
                hardListingCap = Math.max(softListingCap, hardListingCap);
                feeMax = Math.max(feeMin, feeMax);
                maxPrice = Math.max(minPrice, maxPrice);

                lastModified = Files.getLastModifiedTime(configPath).toMillis();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void writeDefault() {
        JsonObject root = new JsonObject();
        root.addProperty("free_listing_slots", freeListingSlots);
        root.addProperty("soft_listing_cap", softListingCap);
        root.addProperty("hard_listing_cap", hardListingCap);
        root.addProperty("base_fee_percent", baseFeePercent);
        root.addProperty("progressive_fee_percent", progressiveFeePercent);
        root.addProperty("fee_min", feeMin);
        root.addProperty("fee_max", feeMax);
        root.addProperty("min_price", minPrice);
        root.addProperty("max_price", maxPrice);
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
