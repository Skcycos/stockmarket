package com.tanrunn.stockmarket.api;

/** Immutable public view of one outstanding limit order. */
public record OrderSnapshot(
        long orderId,
        String stockId,
        boolean buy,
        long priceCents,
        int quantity) {
}
