package com.tanrunn.stockmarket.api;

/** Immutable public view of one settled trade. Prices and fees are integer cents. */
public record TradeSnapshot(
        long dayIndex,
        String stockId,
        boolean buy,
        long priceCents,
        int quantity,
        long feeCents) {
}
