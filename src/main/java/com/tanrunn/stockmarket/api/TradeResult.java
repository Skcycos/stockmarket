package com.tanrunn.stockmarket.api;

/** Result returned by market and limit-order API calls. */
public record TradeResult(
        boolean success,
        String message,
        long orderId,
        long balanceCents) {

    public static TradeResult failure(String message, long balanceCents) {
        return new TradeResult(false, message, -1, balanceCents);
    }
}
