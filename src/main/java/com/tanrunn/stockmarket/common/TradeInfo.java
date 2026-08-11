package com.tanrunn.stockmarket.common;

/** A persisted, server-authoritative fill record shown in the account history. */
public record TradeInfo(
        long dayIndex,
        String stockId,
        boolean buy,
        double price,
        int quantity,
        double fee) {
}
