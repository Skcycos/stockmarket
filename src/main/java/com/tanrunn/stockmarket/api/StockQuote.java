package com.tanrunn.stockmarket.api;

import java.util.List;

/** Immutable public quote view. Prices are integer cents. */
public record StockQuote(
        String id,
        String name,
        long priceCents,
        long prevCloseCents,
        long dayHighCents,
        long dayLowCents,
        long volume,
        List<CandleSnapshot> history,
        String industry,
        boolean halted,
        int haltRemainingCycles) {

    public StockQuote(String id, String name, long priceCents, long prevCloseCents, long dayHighCents,
                      long dayLowCents, long volume, List<CandleSnapshot> history) {
        this(id, name, priceCents, prevCloseCents, dayHighCents, dayLowCents, volume, history,
                "综合", false, 0);
    }

    public StockQuote {
        history = List.copyOf(history);
    }

    public double changePct() {
        if (prevCloseCents <= 0) return 0;
        return (priceCents - prevCloseCents) * 100.0 / prevCloseCents;
    }
}
