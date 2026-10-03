package com.easytrading.paper.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the money part of a player trade.
 *
 * <p>These tests are intentionally server-independent. Inventory and Bukkit
 * event handling are exercised separately on a test server, while this class
 * locks down the arithmetic that must be true before any economy mutation is
 * made.</p>
 */
class TradeSettlementTest {

    @Test
    void transfersOffersInTheExpectedDirectionAndPreservesTotal() {
        TradeSettlement.Result result = TradeSettlement.plan(100L, 25L, 64L, 0L);

        assertTrue(result.valid());
        assertEquals(36L, result.player1Balance());
        assertEquals(89L, result.player2Balance());
        assertEquals(125L, result.player1Balance() + result.player2Balance());
    }

    @Test
    void zeroOffersLeaveBalancesUnchanged() {
        TradeSettlement.Result result = TradeSettlement.plan(123L, 456L, 0L, 0L);

        assertTrue(result.valid());
        assertEquals(123L, result.player1Balance());
        assertEquals(456L, result.player2Balance());
    }

    @Test
    void rejectsAnOfferLargerThanTheOfferingPlayersBalance() {
        TradeSettlement.Result p1Failure = TradeSettlement.plan(63L, 10L, 64L, 0L);
        TradeSettlement.Result p2Failure = TradeSettlement.plan(10L, 63L, 0L, 64L);

        assertFalse(p1Failure.valid());
        assertEquals(TradeSettlement.Failure.PLAYER1_INSUFFICIENT_FUNDS, p1Failure.failure());
        assertFalse(p2Failure.valid());
        assertEquals(TradeSettlement.Failure.PLAYER2_INSUFFICIENT_FUNDS, p2Failure.failure());
    }

    @Test
    void rejectsNegativeInputs() {
        TradeSettlement.Result result = TradeSettlement.plan(100L, 100L, -1L, 0L);

        assertFalse(result.valid());
        assertEquals(TradeSettlement.Failure.NEGATIVE_VALUE, result.failure());
    }

    @Test
    void rejectsResultingBalanceOverflowWithoutReturningPartialState() {
        TradeSettlement.Result result = TradeSettlement.plan(Long.MAX_VALUE, 1L, 0L, 1L);

        assertFalse(result.valid());
        assertEquals(TradeSettlement.Failure.BALANCE_OVERFLOW, result.failure());
        assertEquals(0L, result.player1Balance());
        assertEquals(0L, result.player2Balance());
    }
}
