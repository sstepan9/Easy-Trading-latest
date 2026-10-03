package com.easytrading.paper.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression tests for atomic economy mutations used by player trades. */
class EconomyDataTest {

    private static final UUID PLAYER_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void tradeSettlementCommitsBothSidesTogether(@TempDir Path dataFolder) {
        EconomyData economy = new EconomyData(dataFolder);
        economy.set(PLAYER_1, 100L);
        economy.set(PLAYER_2, 25L);

        EconomyData.TradeSettlementResult status =
                economy.applyTradeSettlement(PLAYER_1, PLAYER_2, 64L, 0L);

        assertEquals(EconomyData.TradeSettlementResult.SUCCESS, status);
        assertEquals(36L, economy.get(PLAYER_1));
        assertEquals(89L, economy.get(PLAYER_2));
    }

    @Test
    void insufficientFundsDoNotPartiallyChargeEitherPlayer(@TempDir Path dataFolder) {
        EconomyData economy = new EconomyData(dataFolder);
        economy.set(PLAYER_1, 63L);
        economy.set(PLAYER_2, 10L);

        EconomyData.TradeSettlementResult status =
                economy.applyTradeSettlement(PLAYER_1, PLAYER_2, 64L, 0L);

        assertEquals(EconomyData.TradeSettlementResult.INSUFFICIENT_FUNDS, status);
        assertEquals(63L, economy.get(PLAYER_1));
        assertEquals(10L, economy.get(PLAYER_2));
    }

    @Test
    void creditOverflowDoesNotDebitTheOfferingPlayer(@TempDir Path dataFolder) {
        EconomyData economy = new EconomyData(dataFolder);
        economy.set(PLAYER_1, 1L);
        economy.set(PLAYER_2, Long.MAX_VALUE);

        EconomyData.TradeSettlementResult status =
                economy.applyTradeSettlement(PLAYER_1, PLAYER_2, 1L, 0L);

        assertEquals(EconomyData.TradeSettlementResult.OVERFLOW, status);
        assertEquals(1L, economy.get(PLAYER_1));
        assertEquals(Long.MAX_VALUE, economy.get(PLAYER_2));
    }

    @Test
    void directTransferIsAtomicAndMovesExactlyTheRequestedAmount(@TempDir Path dataFolder) {
        EconomyData economy = new EconomyData(dataFolder);
        economy.set(PLAYER_1, 100L);
        economy.set(PLAYER_2, 25L);

        EconomyData.MutationResult status = economy.tryTransfer(PLAYER_1, PLAYER_2, 64L);

        assertEquals(EconomyData.MutationResult.SUCCESS, status);
        assertEquals(36L, economy.get(PLAYER_1));
        assertEquals(89L, economy.get(PLAYER_2));
    }

    @Test
    void failedDirectTransferLeavesBothAccountsUnchanged(@TempDir Path dataFolder) {
        EconomyData economy = new EconomyData(dataFolder);
        economy.set(PLAYER_1, 10L);
        economy.set(PLAYER_2, 20L);

        EconomyData.MutationResult status = economy.tryTransfer(PLAYER_1, PLAYER_2, 11L);

        assertEquals(EconomyData.MutationResult.INSUFFICIENT_FUNDS, status);
        assertEquals(10L, economy.get(PLAYER_1));
        assertEquals(20L, economy.get(PLAYER_2));
    }

    @Test
    void overflowingDirectTransferLeavesBothAccountsUnchanged(@TempDir Path dataFolder) {
        EconomyData economy = new EconomyData(dataFolder);
        economy.set(PLAYER_1, 10L);
        economy.set(PLAYER_2, Long.MAX_VALUE);

        EconomyData.MutationResult status = economy.tryTransfer(PLAYER_1, PLAYER_2, 1L);

        assertEquals(EconomyData.MutationResult.OVERFLOW, status);
        assertEquals(10L, economy.get(PLAYER_1));
        assertEquals(Long.MAX_VALUE, economy.get(PLAYER_2));
    }
}
