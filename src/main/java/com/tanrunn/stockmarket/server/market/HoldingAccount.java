package com.tanrunn.stockmarket.server.market;

/**
 * Plain, persistence-free view of a player's account so the trade engine stays
 * unit-testable without Minecraft classes.
 */
public record HoldingAccount(double cash, java.util.Map<String, Integer> holdings) {

    public HoldingAccount {
        holdings = java.util.Map.copyOf(holdings);
    }

    public double totalValue(PriceOf priceOf) {
        double value = cash;
        for (var entry : holdings.entrySet()) {
            value += entry.getValue() * priceOf.priceOf(entry.getKey());
        }
        return Math.round(value * 100.0) / 100.0;
    }

    @FunctionalInterface
    public interface PriceOf {
        double priceOf(String stockId);
    }
}
