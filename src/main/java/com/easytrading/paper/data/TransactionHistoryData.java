package com.easytrading.paper.data;

import com.easytrading.paper.locale.LocalizationManager;
import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Per-player transaction history.
 */
public class TransactionHistoryData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_PER_PLAYER = 100;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());

    public static class Transaction {
        public final String action;
        public final String itemName;
        public final int count;
        public final long amount;
        public final long timestamp;
        public final String otherPlayer;

        public Transaction(String action, String itemName, int count, long amount, long timestamp, String otherPlayer) {
            this.action = action;
            this.itemName = itemName;
            this.count = count;
            this.amount = amount;
            this.timestamp = timestamp;
            this.otherPlayer = otherPlayer;
        }

        public String format(LocalizationManager localization) {
            String time = FORMATTER.format(Instant.ofEpochMilli(timestamp));
            String sign = amount >= 0 ? "+" : "";
            return switch (action) {
                case "BUY" -> localization.tr("history.buy", time, itemName, count, Math.abs(amount));
                case "SELL" -> localization.tr("history.sell", time, itemName, count, amount);
                case "SELL_INCOME" -> localization.tr("history.sell_income", time, itemName, count, amount);
                case "PURCHASE_RESERVE" -> localization.tr("history.purchase_reserve", time, Math.abs(amount), itemName, count);
                case "PURCHASE_REFUND" -> localization.tr("history.purchase_refund", time, itemName, count, amount);
                case "PURCHASE_FILL" -> localization.tr("history.purchase_fill", time, itemName, count, Math.abs(amount));
                case "SELL_TO_ORDER" -> localization.tr("history.sell_to_order", time,
                        otherPlayer == null || otherPlayer.isBlank() ? localization.tr("history.purchase_order_label") : otherPlayer,
                        itemName, count, amount);
                case "SEND" -> localization.tr("history.send", time, otherPlayer == null ? "" : otherPlayer, sign, amount);
                case "BANK_BUY" -> localization.tr("history.bank_buy", time, itemName, count, Math.abs(amount));
                case "BANK_SELL" -> localization.tr("history.bank_sell", time, itemName, count, amount);
                case "FEE" -> localization.tr("history.fee", time, Math.abs(amount));
                case "TRADE" -> localization.tr("history.trade", time, otherPlayer == null ? itemName : otherPlayer, count, sign, amount);
                default -> localization.tr("history.default", time, action, sign, amount);
            };
        }
    }

    private final Map<UUID, LinkedList<Transaction>> history = new HashMap<>();
    private final Path file;

    public TransactionHistoryData(Path dataFolder) {
        this.file = dataFolder.resolve("history.json");
        load();
    }

    public synchronized void record(UUID player, String action, String itemName, int count, long amount, String otherPlayer) {
        LinkedList<Transaction> list = history.computeIfAbsent(player, k -> new LinkedList<>());
        list.addFirst(new Transaction(action, itemName, count, amount, System.currentTimeMillis(), otherPlayer));
        while (list.size() > MAX_PER_PLAYER) list.removeLast();
    }

    public synchronized List<Transaction> getRecent(UUID player, int limit) {
        LinkedList<Transaction> list = history.get(player);
        if (list == null || list.isEmpty()) return List.of();
        int count = Math.min(limit, list.size());
        return new ArrayList<>(list.subList(0, count));
    }

    public void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            history.clear();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    JsonArray arr = entry.getValue().getAsJsonArray();
                    LinkedList<Transaction> list = new LinkedList<>();
                    for (JsonElement el : arr) {
                        JsonObject obj = el.getAsJsonObject();
                        list.add(new Transaction(
                                obj.get("action").getAsString(),
                                obj.has("itemName") ? obj.get("itemName").getAsString() : "",
                                obj.has("count") ? obj.get("count").getAsInt() : 0,
                                obj.get("amount").getAsLong(),
                                obj.has("timestamp") ? obj.get("timestamp").getAsLong() : 0,
                                obj.has("otherPlayer") ? obj.get("otherPlayer").getAsString() : ""
                        ));
                    }
                    history.put(uuid, list);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, LinkedList<Transaction>> entry : history.entrySet()) {
                JsonArray arr = new JsonArray();
                for (Transaction t : entry.getValue()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("action", t.action);
                    obj.addProperty("itemName", t.itemName);
                    obj.addProperty("count", t.count);
                    obj.addProperty("amount", t.amount);
                    obj.addProperty("timestamp", t.timestamp);
                    obj.addProperty("otherPlayer", t.otherPlayer == null ? "" : t.otherPlayer);
                    arr.add(obj);
                }
                root.add(entry.getKey().toString(), arr);
            }
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
