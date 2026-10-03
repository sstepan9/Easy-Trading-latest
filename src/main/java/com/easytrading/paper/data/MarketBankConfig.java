package com.easytrading.paper.data;

import com.easytrading.paper.gui.MarketGui;
import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Bank config — resource pricing with stock elasticity.
 * Config file: plugins/EasyTrading/bank-config.json
 */
public class MarketBankConfig {
    public static class BankResource {
        private volatile int limit;
        private volatile long base;
        private volatile long target;
        private volatile long min;
        private volatile long max;
        private volatile long stock;
        private volatile long price;

        public BankResource(int limit, long base, long target, long min, long max, long stock, long price) {
            this.limit = Math.max(0, limit);
            this.base = Math.max(1L, base);
            this.target = Math.max(1L, target);
            this.min = Math.max(1L, min);
            this.max = Math.max(this.min, max);
            this.stock = Math.max(0L, stock);
            setPrice(price);
        }

        public int limit() { return limit; }
        public long base() { return base; }
        public long target() { return target; }
        public long min() { return min; }
        public long max() { return max; }
        public long stock() { return stock; }
        public long price() { return price; }
        public void setStock(long stock) { this.stock = Math.max(0, stock); }
        public void setPrice(long price) {
            this.price = Math.max(min, Math.min(max, price));
        }
    }

    public static final String[] BUY_SUGGESTIONS = {
            "coal", "redstone", "lapis", "copper", "iron", "gold", "diamond", "emerald", "netherite",
            "уголь", "редстоун", "лазурит", "медь", "железо", "золото", "алмаз", "изумруд", "незерит"
    };

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** Map from minecraft item id (e.g. "minecraft:coal") to resource config */
    private final Map<String, BankResource> resources = new HashMap<>();
    private int taxPercent = 12;
    private double priceElasticity = 0.60;
    private double minFactor = 0.55;
    private double maxFactor = 2.10;
    private int maxStepPercent = 12;
    private int dailyStockRecoveryPercent = 18;
    private int buyLimitMultiplier = 1;
    private String lastRebalanceDate = "";
    private long lastModified = -1L;

    private final Path configPath;
    private final Path dataFolder;

    public MarketBankConfig(Path dataFolder) {
        this.dataFolder = dataFolder;
        this.configPath = dataFolder.resolve("bank-config.json");
    }

    public synchronized BankResource get(String itemId) {
        return resources.get(itemId);
    }

    public synchronized Map<String, BankResource> getResources() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(resources));
    }

    public synchronized int getTaxPercent() { return taxPercent; }

    public synchronized void setTaxPercent(int taxPercent) {
        this.taxPercent = Math.max(0, Math.min(100, taxPercent));
        save();
    }

    public static String getDisplayName(String itemId) {
        Material material = toMaterial(itemId);
        return material == null ? itemId : MarketGui.getItemName(new ItemStack(material));
    }

    public String resolveResourceId(String input) {
        if (input == null) return null;
        String key = input.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "coal", "minecraft:coal", "уголь" -> "minecraft:coal";
            case "redstone", "minecraft:redstone", "редстоун" -> "minecraft:redstone";
            case "lapis", "lapis_lazuli", "minecraft:lapis_lazuli", "лазурит", "лазурь" -> "minecraft:lapis_lazuli";
            case "copper", "copper_ingot", "minecraft:copper_ingot", "медь" -> "minecraft:copper_ingot";
            case "iron", "iron_ingot", "minecraft:iron_ingot", "железо" -> "minecraft:iron_ingot";
            case "gold", "gold_ingot", "minecraft:gold_ingot", "золото" -> "minecraft:gold_ingot";
            case "diamond", "minecraft:diamond", "алмаз" -> "minecraft:diamond";
            case "emerald", "minecraft:emerald", "изумруд" -> "minecraft:emerald";
            case "netherite", "netherite_ingot", "minecraft:netherite_ingot", "незерит" -> "minecraft:netherite_ingot";
            default -> null;
        };
    }

    public synchronized void ensureLoaded() {
        reloadIfChanged();
        ensureDailyRebalance();
    }

    public synchronized void reload() {
        load(true);
    }

    private synchronized void reloadIfChanged() {
        try {
            if (Files.exists(configPath)) {
                long modified = Files.getLastModifiedTime(configPath).toMillis();
                if (modified != lastModified) {
                    load(false);
                }
            } else {
                load(true);
            }
        } catch (IOException e) {
            // ignore
        }
    }

    private synchronized void load(boolean createIfMissing) {
        if (!Files.exists(configPath)) {
            if (createIfMissing) writeDefault();
        }
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                if (root.has("tax_percent")) taxPercent = Math.max(0, Math.min(100, root.get("tax_percent").getAsInt()));
                if (root.has("price_elasticity")) priceElasticity = clampFinite(root.get("price_elasticity").getAsDouble(), 0.60, 0.05, 3.0);
                if (root.has("min_factor")) minFactor = clampFinite(root.get("min_factor").getAsDouble(), 0.55, 0.05, 1.0);
                if (root.has("max_factor")) maxFactor = clampFinite(root.get("max_factor").getAsDouble(), 2.10, 1.01, 1000.0);
                if (root.has("max_step_percent")) maxStepPercent = Math.max(0, Math.min(100, root.get("max_step_percent").getAsInt()));
                if (root.has("daily_stock_recovery_percent")) dailyStockRecoveryPercent = Math.max(0, Math.min(100, root.get("daily_stock_recovery_percent").getAsInt()));
                if (root.has("buy_limit_multiplier")) buyLimitMultiplier = Math.max(1, root.get("buy_limit_multiplier").getAsInt());
                if (root.has("last_rebalance_date")) lastRebalanceDate = root.get("last_rebalance_date").getAsString();

                minFactor = Math.max(0.05, Math.min(1.0, minFactor));
                maxFactor = Math.max(minFactor + 0.05, maxFactor);
                buyLimitMultiplier = Math.max(1, buyLimitMultiplier);

                JsonObject res = root.has("resources") && root.get("resources").isJsonObject()
                        ? root.getAsJsonObject("resources") : new JsonObject();
                resources.clear();
                for (String rKey : res.keySet()) {
                    try {
                        JsonObject entry = res.getAsJsonObject(rKey);
                        if (entry == null || rKey == null || rKey.isBlank() || !entry.has("limit")) continue;
                        int limit = Math.max(0, entry.get("limit").getAsInt());
                        long base = entry.has("base") ? entry.get("base").getAsLong()
                                : (entry.has("price") ? entry.get("price").getAsLong() : 1L);
                        base = Math.max(1L, base);
                        long target = Math.max(1L, entry.has("target") ? entry.get("target").getAsLong() : limit);
                        long rmin = entry.has("min") ? Math.max(1L, entry.get("min").getAsLong())
                                : Math.max(1L, Math.round(base * minFactor));
                        long minimumMax = rmin == Long.MAX_VALUE ? Long.MAX_VALUE : rmin + 1;
                        long rmax = entry.has("max") ? Math.max(rmin, entry.get("max").getAsLong())
                                : Math.max(minimumMax, Math.round(base * maxFactor));
                        if (rmax < rmin) rmax = rmin;
                        long stock = entry.has("stock") ? Math.max(0L, entry.get("stock").getAsLong()) : target;
                        long price = entry.has("price") ? entry.get("price").getAsLong() : base;
                        BankResource resObj = new BankResource(limit, base, target, rmin, rmax, stock, price);
                        resObj.setPrice(Math.max(resObj.min(), Math.min(resObj.max(), price)));
                        resObj.setPrice(computePrice(resObj, false));
                        resources.put(rKey, resObj);
                    } catch (RuntimeException ignored) {
                        // Ignore only the malformed resource and keep valid
                        // bank entries available.
                    }
                }
                lastModified = Files.getLastModifiedTime(configPath).toMillis();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private synchronized void writeDefault() {
        JsonObject root = new JsonObject();
        JsonObject res = new JsonObject();
        addDefault(res, "minecraft:coal", 256, 2);
        addDefault(res, "minecraft:redstone", 192, 3);
        addDefault(res, "minecraft:lapis_lazuli", 192, 4);
        addDefault(res, "minecraft:copper_ingot", 160, 7);
        addDefault(res, "minecraft:iron_ingot", 96, 12);
        addDefault(res, "minecraft:gold_ingot", 64, 18);
        addDefault(res, "minecraft:diamond", 24, 85);
        addDefault(res, "minecraft:emerald", 20, 70);
        addDefault(res, "minecraft:netherite_ingot", 2, 4500);
        root.addProperty("tax_percent", taxPercent);
        root.addProperty("price_elasticity", priceElasticity);
        root.addProperty("min_factor", minFactor);
        root.addProperty("max_factor", maxFactor);
        root.addProperty("max_step_percent", maxStepPercent);
        root.addProperty("daily_stock_recovery_percent", dailyStockRecoveryPercent);
        root.addProperty("buy_limit_multiplier", buyLimitMultiplier);
        root.addProperty("last_rebalance_date", lastRebalanceDate);
        root.add("resources", res);
        try {
            Files.createDirectories(configPath.getParent());
            DataFileUtil.writeStringAtomic(configPath, GSON.toJson(root));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void addDefault(JsonObject res, String id, int limit, long base) {
        JsonObject obj = new JsonObject();
        obj.addProperty("limit", limit);
        obj.addProperty("base", base);
        obj.addProperty("target", limit);
        obj.addProperty("min", Math.max(1L, Math.round(base * minFactor)));
        long minimumMax = base == Long.MAX_VALUE ? Long.MAX_VALUE : base + 1;
        obj.addProperty("max", Math.max(Math.round(base * maxFactor), minimumMax));
        obj.addProperty("stock", limit);
        obj.addProperty("price", base);
        res.add(id, obj);
    }

    public synchronized long getBuyPrice(String itemId) {
        BankResource res = get(itemId);
        if (res == null) return 0L;
        return applyTaxUp(res.price(), taxPercent);
    }

    public synchronized long getSellPrice(String itemId) {
        BankResource res = get(itemId);
        if (res == null) return 0L;
        return applyTaxDown(res.price(), taxPercent);
    }

    public synchronized int getBuyLimit(BankResource resource) {
        if (resource == null) return 0;
        long scaled = (long) Math.max(0, resource.limit()) * Math.max(1, buyLimitMultiplier);
        return (int) Math.min(Integer.MAX_VALUE, scaled);
    }

    public synchronized void applyStockChange(String itemId, long delta) {
        BankResource res = resources.get(itemId);
        if (res == null) return;
        long current = Math.max(0L, res.stock());
        long next;
        if (delta > 0 && current > Long.MAX_VALUE - delta) {
            next = Long.MAX_VALUE;
        } else if (delta == Long.MIN_VALUE || (delta < 0 && current < -delta)) {
            next = 0L;
        } else {
            next = current + delta;
        }
        res.setStock(next);
        res.setPrice(computePrice(res, true));
        save();
    }

    public synchronized void ensureDailyRebalance() {
        String today = LocalDate.now().toString();
        if (today.equals(lastRebalanceDate)) return;
        if (!resources.isEmpty()) {
            rebalanceStocks();
            for (BankResource res : resources.values()) {
                res.setPrice(computePrice(res, true));
            }
        }
        lastRebalanceDate = today;
        save();
    }

    private void rebalanceStocks() {
        for (BankResource res : resources.values()) {
            long target = Math.max(1L, res.target());
            long stock = Math.max(0L, res.stock());
            long drift = target - stock;
            if (drift == 0L) continue;
            long recover = Math.round(Math.abs(drift) * (dailyStockRecoveryPercent / 100.0));
            if (recover <= 0L) recover = 1L;
            long adjusted;
            if (drift > 0L) {
                adjusted = stock > Long.MAX_VALUE - recover ? Long.MAX_VALUE : stock + recover;
            } else {
                adjusted = stock < recover ? 0L : stock - recover;
            }
            if (drift > 0L) adjusted = Math.min(target, adjusted);
            else adjusted = Math.max(target, adjusted);
            res.setStock(Math.max(1L, adjusted));
        }
    }

    private long computePrice(BankResource res, boolean smooth) {
        if (res.base() <= 0) return 0;
        double stock = Math.max(1.0, res.stock());
        double target = Math.max(1.0, res.target());
        double ratio = target / stock;
        double equilibrium = res.base() * Math.pow(ratio, priceElasticity);
        if (!Double.isFinite(equilibrium)) {
            equilibrium = equilibrium < 0 ? res.min() : res.max();
        }
        double bounded = Math.max(res.min(), Math.min(res.max(), equilibrium));
        if (!smooth) return clampRounded(bounded, res.min(), res.max());

        double current = Math.max(res.min(), Math.min(res.max(), res.price()));
        double maxStep = current * (maxStepPercent / 100.0);
        if (maxStep < 1.0) maxStep = 1.0;
        double delta = bounded - current;
        double limited = current + Math.max(-maxStep, Math.min(maxStep, delta));
        return clampRounded(Math.max(res.min(), Math.min(res.max(), limited)), res.min(), res.max());
    }

    private static long clampRounded(double value, long min, long max) {
        if (!Double.isFinite(value)) return value < 0 ? min : max;
        long rounded = Math.round(value);
        return Math.max(min, Math.min(max, rounded));
    }

    private static double clampFinite(double value, double fallback, double min, double max) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private long applyTaxUp(long value, int taxPct) {
        if (value <= 0) return 1L;
        BigDecimal result = BigDecimal.valueOf(value)
                .multiply(BigDecimal.valueOf(100L + Math.max(0, taxPct)))
                .divide(BigDecimal.valueOf(100L), 0, RoundingMode.CEILING);
        return result.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE : Math.max(1L, result.longValue());
    }

    private long applyTaxDown(long value, int taxPct) {
        if (value <= 0) return 1L;
        BigDecimal result = BigDecimal.valueOf(value)
                .multiply(BigDecimal.valueOf(Math.max(0, 100 - taxPct)))
                .divide(BigDecimal.valueOf(100L), 0, RoundingMode.FLOOR);
        return Math.max(1L, result.longValue());
    }

    public synchronized void save() {
        JsonObject root = new JsonObject();
        root.addProperty("tax_percent", taxPercent);
        root.addProperty("price_elasticity", priceElasticity);
        root.addProperty("min_factor", minFactor);
        root.addProperty("max_factor", maxFactor);
        root.addProperty("max_step_percent", maxStepPercent);
        root.addProperty("daily_stock_recovery_percent", dailyStockRecoveryPercent);
        root.addProperty("buy_limit_multiplier", buyLimitMultiplier);
        root.addProperty("last_rebalance_date", lastRebalanceDate);
        JsonObject res = new JsonObject();
        for (Map.Entry<String, BankResource> e : resources.entrySet()) {
            BankResource r = e.getValue();
            JsonObject obj = new JsonObject();
            obj.addProperty("limit", r.limit());
            obj.addProperty("base", r.base());
            obj.addProperty("target", r.target());
            obj.addProperty("min", r.min());
            obj.addProperty("max", r.max());
            obj.addProperty("stock", r.stock());
            obj.addProperty("price", r.price());
            res.add(e.getKey(), obj);
        }
        root.add("resources", res);
        try {
            Files.createDirectories(configPath.getParent());
            DataFileUtil.writeStringAtomic(configPath, GSON.toJson(root));
            lastModified = Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Build rates info for display */
    public synchronized List<BankRateEntry> buildRatesList() {
        List<BankRateEntry> entries = new ArrayList<>();
        for (Map.Entry<String, BankResource> entry : resources.entrySet()) {
            BankResource res = entry.getValue();
            long mid = res.price();
            long buy = applyTaxUp(mid, taxPercent);
            long sell = applyTaxDown(mid, taxPercent);
            entries.add(new BankRateEntry(entry.getKey(), buy, sell));
        }
        return entries;
    }

    public record BankRateEntry(String itemId, long buyPrice, long sellPrice) {}

    // ── Logging ──

    public static synchronized void logTrade(
            Path dataFolder, Player player, String action, String itemId, String itemName,
            int requested, int inHand, int accepted, long pricePerItem, long total,
            int limit, int remaining, long balanceBefore, long balanceAfter,
            String status, String reason
    ) {
        try {
            Path logDir = dataFolder.resolve("logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("easytrading-bank.log");
            String line = buildLogLine(player, action, itemId, itemName, requested, accepted,
                    pricePerItem, total, limit, remaining, balanceBefore, balanceAfter, inHand, status, reason);
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String buildLogLine(
            Player player, String action, String itemId, String itemName,
            int requested, int accepted, long pricePerItem, long total,
            int limit, int remaining, long balanceBefore, long balanceAfter,
            int inHand, String status, String reason
    ) {
        String iso = ISO.format(Instant.now());
        String local = LOCAL.format(Instant.now());
        String world = player.getWorld().getName();
        String pos = player.getLocation().getBlockX() + "," + player.getLocation().getBlockY() + "," + player.getLocation().getBlockZ();
        return String.join(" | ",
                "ts_iso=" + iso, "ts_local=" + local,
                "action=" + action, "status=" + status, "reason=" + reason,
                "player=" + player.getName(), "uuid=" + player.getUniqueId(),
                "item_id=" + itemId, "item_name=" + itemName,
                "requested=" + requested, "in_hand=" + inHand, "accepted=" + accepted,
                "price=" + pricePerItem, "total=" + total,
                "limit=" + limit, "remaining=" + remaining,
                "balance_before=" + balanceBefore, "balance_after=" + balanceAfter,
                "world=" + world, "pos=" + pos
        );
    }

    /** Convert minecraft item id to Bukkit Material */
    public static Material toMaterial(String minecraftId) {
        // "minecraft:coal" -> "COAL", "minecraft:iron_ingot" -> "IRON_INGOT"
        if (minecraftId == null || minecraftId.isBlank()) return null;
        String name = minecraftId.replace("minecraft:", "").toUpperCase(Locale.ROOT);
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Convert Bukkit Material to minecraft item id */
    public static String toMinecraftId(Material mat) {
        return "minecraft:" + mat.name().toLowerCase();
    }
}
