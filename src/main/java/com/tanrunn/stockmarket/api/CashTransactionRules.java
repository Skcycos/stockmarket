package com.tanrunn.stockmarket.api;

/** Pure validation/arithmetic shared by the cross-Mod cash API. */
public final class CashTransactionRules {
    private CashTransactionRules() {
    }

    public static String validatePositiveAmount(long cents) {
        return cents > 0 ? null : "金额必须大于 0";
    }

    public static boolean canWithdraw(long balanceCents, long amountCents) {
        return amountCents > 0 && balanceCents >= amountCents;
    }

    public static long applyDelta(long balanceCents, long deltaCents) {
        return Math.addExact(balanceCents, deltaCents);
    }
}
