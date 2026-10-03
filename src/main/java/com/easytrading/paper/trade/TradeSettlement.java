package com.easytrading.paper.trade;

/**
 * Pure, server-independent validation and balance calculation for a player trade.
 *
 * <p>The GUI and Bukkit inventory code deliberately do not live here. Keeping
 * the arithmetic in a small pure type makes it possible to test the most
 * important money invariant without a running server:</p>
 *
 * <pre>
 *   resulting balance = current balance - offered amount + amount received
 * </pre>
 */
public final class TradeSettlement {

    private TradeSettlement() {
    }

    public enum Failure {
        NONE,
        NEGATIVE_VALUE,
        PLAYER1_INSUFFICIENT_FUNDS,
        PLAYER2_INSUFFICIENT_FUNDS,
        BALANCE_OVERFLOW
    }

    /**
     * Result of planning a settlement. Invalid results never contain balances
     * that should be written to the economy.
     */
    public record Result(Failure failure, long player1Balance, long player2Balance) {
        public boolean valid() {
            return failure == Failure.NONE;
        }

        public static Result failure(Failure failure) {
            return new Result(failure, 0L, 0L);
        }
    }

    /**
     * Validate both offers and calculate both final balances without mutating
     * any state. All values are integer coins and must be non-negative.
     */
    public static Result plan(long player1Balance, long player2Balance,
                              long player1Offer, long player2Offer) {
        if (player1Balance < 0L || player2Balance < 0L
                || player1Offer < 0L || player2Offer < 0L) {
            return Result.failure(Failure.NEGATIVE_VALUE);
        }
        if (player1Offer > player1Balance) {
            return Result.failure(Failure.PLAYER1_INSUFFICIENT_FUNDS);
        }
        if (player2Offer > player2Balance) {
            return Result.failure(Failure.PLAYER2_INSUFFICIENT_FUNDS);
        }

        // Subtraction is safe after the checks above. Addition may overflow
        // even though each individual account was valid (e.g. MAX_VALUE plus
        // a payment from the other player), so use exact arithmetic.
        try {
            long result1 = Math.addExact(player1Balance - player1Offer, player2Offer);
            long result2 = Math.addExact(player2Balance - player2Offer, player1Offer);
            return new Result(Failure.NONE, result1, result2);
        } catch (ArithmeticException overflow) {
            return Result.failure(Failure.BALANCE_OVERFLOW);
        }
    }
}
