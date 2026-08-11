package com.tanrunn.stockmarket.api;

/** Result returned by deposit/withdraw operations. Amounts are integer cents. */
public record TransactionResult(
        boolean success,
        String message,
        long balanceCents,
        String transactionId,
        boolean duplicate) {

    public static TransactionResult failure(String message, long balanceCents) {
        return new TransactionResult(false, message, balanceCents, "", false);
    }
}
