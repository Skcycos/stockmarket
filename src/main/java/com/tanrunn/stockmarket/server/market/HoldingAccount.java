package com.tanrunn.stockmarket.server.market;

/**
 * Plain, persistence-free view of a player's account so the trade engine stays
 * unit-testable without Minecraft classes.
 */
public record HoldingAccount(
        double cash,
        java.util.Map<String, Integer> holdings,
        java.util.Map<String, Double> costBasis,
        double realizedPnl) {

    public HoldingAccount(double cash, java.util.Map<String, Integer> holdings) {
        this(cash, holdings, java.util.Map.of(), 0);
    }

    public HoldingAccount {
        holdings = java.util.Map.copyOf(holdings);
        costBasis = java.util.Map.copyOf(costBasis);
    }

    public double totalValue(PriceOf priceOf) {
        return round2(cash + holdingsValue(priceOf));
    }

    public double holdingsValue(PriceOf priceOf) {
        double value = 0;
        for (var entry : holdings.entrySet()) {
            value += entry.getValue() * priceOf.priceOf(entry.getKey());
        }
        return round2(value);
    }

    public double unrealizedPnl(PriceOf priceOf) {
        double pnl = 0;
        for (var entry : holdings.entrySet()) {
            pnl += entry.getValue() * priceOf.priceOf(entry.getKey())
                    - costBasis.getOrDefault(entry.getKey(), 0.0);
        }
        return round2(pnl);
    }

    public double totalPnl(PriceOf priceOf) {
        return round2(realizedPnl + unrealizedPnl(priceOf));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @FunctionalInterface
    public interface PriceOf {
        double priceOf(String stockId);
    }
}
