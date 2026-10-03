package com.easytrading.paper.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit log for player-to-player trades.
 *
 * <p>Logging is deliberately best-effort and never changes the outcome of a
 * trade.  Every line contains both the settlement inputs and the verification
 * flags, making a reported missing payout diagnosable after a restart.</p>
 */
public final class TradeAuditLog {
    private static final Object LOCK = new Object();

    private TradeAuditLog() {
    }

    public static boolean append(
            Path dataFolder,
            String outcome,
            String reason,
            UUID player1,
            String player1Name,
            UUID player2,
            String player2Name,
            long player1BalanceBefore,
            long player2BalanceBefore,
            long player1BalanceAfter,
            long player2BalanceAfter,
            long player1ExpectedAfter,
            long player2ExpectedAfter,
            long player1MoneyOffer,
            long player2MoneyOffer,
            long player1Items,
            long player2Items,
            boolean escrowCleared,
            boolean balancesVerified,
            boolean deliveryVerified,
            boolean economyPersisted,
            String details
    ) {
        if (dataFolder == null) return false;

        String line = String.join(" | ",
                "ts=" + Instant.now(),
                "outcome=" + safe(outcome),
                "verification=" + (escrowCleared && balancesVerified && deliveryVerified && economyPersisted ? "PASS" : "CHECK"),
                "reason=" + safe(reason),
                "player1=" + safe(player1) + ":" + safe(player1Name),
                "player2=" + safe(player2) + ":" + safe(player2Name),
                "balance1_before=" + player1BalanceBefore,
                "balance2_before=" + player2BalanceBefore,
                "balance1_after=" + player1BalanceAfter,
                "balance2_after=" + player2BalanceAfter,
                "balance1_expected=" + player1ExpectedAfter,
                "balance2_expected=" + player2ExpectedAfter,
                "money1_offer=" + player1MoneyOffer,
                "money2_offer=" + player2MoneyOffer,
                "items1=" + player1Items,
                "items2=" + player2Items,
                "escrow_cleared=" + escrowCleared,
                "balances_verified=" + balancesVerified,
                "delivery_verified=" + deliveryVerified,
                "economy_persisted=" + economyPersisted,
                "details=" + safe(details)
        ) + System.lineSeparator();

        Path logFile = dataFolder.resolve("logs").resolve("easytrading-trades.log");
        synchronized (LOCK) {
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                return true;
            } catch (IOException | RuntimeException ignored) {
                return false;
            }
        }
    }

    private static String safe(Object value) {
        if (value == null) return "-";
        return String.valueOf(value)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('|', '/');
    }
}
