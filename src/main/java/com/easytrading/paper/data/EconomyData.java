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
import java.util.logging.Logger;

public class EconomyData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Path file;
    private final Logger logger;

    /**
     * Result of a checked balance mutation.  Economy operations are performed
     * on the server thread in normal use, but they still need to be atomic: a
     * balance check followed by two independent {@code add} calls can otherwise
     * be interleaved by another command/Vault consumer.
     */
    public enum MutationResult {
        SUCCESS,
        INVALID_PLAYER,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        OVERFLOW
    }

    /** Result values for a two-sided trade settlement. */
    public enum TradeSettlementResult {
        SUCCESS,
        INVALID_PLAYER,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        OVERFLOW
    }

    public EconomyData(Path dataFolder) {
        this(dataFolder, Logger.getLogger(EconomyData.class.getName()));
    }

    public EconomyData(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("economy.json");
        this.logger = Objects.requireNonNull(logger, "logger");
        load();
    }

    public synchronized long get(UUID player) {
        return player == null ? 0L : balances.getOrDefault(player, 0L);
    }

    public synchronized void set(UUID player, long amount) {
        if (player == null) {
            throw new IllegalArgumentException("player cannot be null");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("balance cannot be negative");
        }
        balances.put(player, amount);
    }

    /**
     * Legacy unchecked-looking mutator retained for source compatibility.
     * Invalid or overflowing mutations are rejected rather than wrapping a
     * signed long and creating a negative/huge balance.
     */
    public synchronized void add(UUID player, long delta) {
        if (player == null) {
            return;
        }
        long current = get(player);
        if (delta == 0) {
            return;
        }
        if (delta > 0) {
            if (current > Long.MAX_VALUE - delta) {
                return;
            }
            balances.put(player, current + delta);
            return;
        }

        // Avoid overflow in negating Long.MIN_VALUE and never allow a negative
        // balance.  Callers that need to know why an operation failed should
        // use tryDeposit/tryWithdraw/tryTransfer below.
        if (delta == Long.MIN_VALUE || current < -delta) {
            return;
        }
        balances.put(player, current + delta);
    }

    /** Atomically withdraws a non-negative amount, if the player can afford it. */
    public synchronized MutationResult tryWithdraw(UUID player, long amount) {
        if (player == null) return MutationResult.INVALID_PLAYER;
        if (amount < 0) return MutationResult.INVALID_AMOUNT;
        long current = get(player);
        if (current < amount) return MutationResult.INSUFFICIENT_FUNDS;
        // current >= amount guarantees subtraction cannot underflow.
        balances.put(player, current - amount);
        return MutationResult.SUCCESS;
    }

    /** Returns whether a withdrawal could succeed at this instant. */
    public synchronized boolean canWithdraw(UUID player, long amount) {
        return player != null && amount >= 0 && get(player) >= amount;
    }

    /** Atomically deposits a non-negative amount, rejecting long overflow. */
    public synchronized MutationResult tryDeposit(UUID player, long amount) {
        if (player == null) return MutationResult.INVALID_PLAYER;
        if (amount < 0) return MutationResult.INVALID_AMOUNT;
        long current = get(player);
        if (current > Long.MAX_VALUE - amount) return MutationResult.OVERFLOW;
        balances.put(player, current + amount);
        return MutationResult.SUCCESS;
    }

    /** Returns whether a deposit could succeed without exceeding long range. */
    public synchronized boolean canDeposit(UUID player, long amount) {
        if (player == null || amount < 0) return false;
        return get(player) <= Long.MAX_VALUE - amount;
    }

    /**
     * Atomically transfers funds between two accounts.  Neither account is
     * changed when validation fails (including credit overflow).
     */
    public synchronized MutationResult tryTransfer(UUID from, UUID to, long amount) {
        if (from == null || to == null || from.equals(to)) {
            return MutationResult.INVALID_PLAYER;
        }
        if (amount < 0) return MutationResult.INVALID_AMOUNT;
        long fromBalance = get(from);
        long toBalance = get(to);
        if (fromBalance < amount) return MutationResult.INSUFFICIENT_FUNDS;
        if (toBalance > Long.MAX_VALUE - amount) return MutationResult.OVERFLOW;
        balances.put(from, fromBalance - amount);
        balances.put(to, toBalance + amount);
        return MutationResult.SUCCESS;
    }

    /**
     * Atomically settles a player-to-player trade.  {@code player1Offer} is
     * paid by player 1 to player 2 and vice versa for {@code player2Offer}.
     * All balances are validated before any mutation, preventing the classic
     * partial-settlement bug where one side is charged but the other is not.
     */
    public synchronized TradeSettlementResult applyTradeSettlement(
            UUID player1, UUID player2, long player1Offer, long player2Offer) {
        if (player1 == null || player2 == null || player1.equals(player2)) {
            return TradeSettlementResult.INVALID_PLAYER;
        }
        if (player1Offer < 0 || player2Offer < 0) {
            return TradeSettlementResult.INVALID_AMOUNT;
        }

        long balance1 = get(player1);
        long balance2 = get(player2);
        if (balance1 < player1Offer || balance2 < player2Offer) {
            return TradeSettlementResult.INSUFFICIENT_FUNDS;
        }

        // Compute every final value before writing anything.  The additions
        // are checked explicitly so a malicious/admin-sized balance cannot
        // wrap around into a negative number.
        long final1;
        long final2;
        if (balance1 - player1Offer > Long.MAX_VALUE - player2Offer
                || balance2 - player2Offer > Long.MAX_VALUE - player1Offer) {
            return TradeSettlementResult.OVERFLOW;
        }
        final1 = balance1 - player1Offer + player2Offer;
        final2 = balance2 - player2Offer + player1Offer;

        balances.put(player1, final1);
        balances.put(player2, final2);
        return TradeSettlementResult.SUCCESS;
    }

    /** Convenience boolean form for callers that only need success/failure. */
    public synchronized boolean tryApplyTradeSettlement(
            UUID player1, UUID player2, long player1Offer, long player2Offer) {
        return applyTradeSettlement(player1, player2, player1Offer, player2Offer)
                == TradeSettlementResult.SUCCESS;
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
                    long amount = entry.getValue().getAsLong();
                    // Do not resurrect corrupt/legacy negative balances.  A
                    // malformed account is treated as a zero balance and can
                    // be repaired by normal deposits/admin tooling.
                    if (amount >= 0) {
                        balances.put(uuid, amount);
                    }
                } catch (Exception entryFailure) {
                    logger.warning("Skipping corrupt economy balance for '" + entry.getKey()
                            + "': " + entryFailure.getMessage());
                }
            }
        } catch (Exception e) {
            balances.clear();
            DataFileUtil.backupCorrupted(file, logger, e);
        }
    }

    public synchronized boolean save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Long> e : balances.entrySet()) {
                root.addProperty(e.getKey().toString(), e.getValue());
            }
            DataFileUtil.writeStringAtomic(file, GSON.toJson(root));
            return true;
        } catch (IOException e) {
            logger.warning("Could not save economy data: " + e.getMessage());
            return false;
        }
    }
}
