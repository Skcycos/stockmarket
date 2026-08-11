package com.tanrunn.stockmarket.api;

/** Persistent cash-ledger entry created by the cross-mod account API. */
public record TransactionRecord(
        String transactionId,
        String requestId,
        long dayIndex,
        long deltaCents,
        long balanceCents,
        String source,
        String reason) {
}
