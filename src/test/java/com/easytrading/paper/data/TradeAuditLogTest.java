package com.easytrading.paper.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeAuditLogTest {
    @Test
    void writesVerificationFields(@TempDir Path dataFolder) throws Exception {
        boolean written = TradeAuditLog.append(dataFolder, "COMMITTED", "verified",
                UUID.randomUUID(), "one", UUID.randomUUID(), "two",
                100, 25, 36, 89, 36, 89, 64, 0,
                64, 0, true, true, true, true, "normal");

        assertTrue(written);
        String log = Files.readString(dataFolder.resolve("logs/easytrading-trades.log"));
        assertTrue(log.contains("verification=PASS"));
        assertTrue(log.contains("balances_verified=true"));
        assertTrue(log.contains("delivery_verified=true"));
        assertTrue(log.contains("economy_persisted=true"));
    }
}
