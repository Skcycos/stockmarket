package com.tanrunn.stockmarket.common;

import java.util.List;

public record StockInfo(
        String id,
        String name,
        double price,
        double prevClose,
        double dayHigh,
        double dayLow,
        long volume,
        List<Candle> history,
        String industry,
        boolean halted,
        int haltRemainingCycles) {

    /** Compatibility constructor for integrations and old tests. */
    public StockInfo(String id, String name, double price, double prevClose, double dayHigh, double dayLow,
                     long volume, List<Candle> history) {
        this(id, name, price, prevClose, dayHigh, dayLow, volume, history, "综合", false, 0);
    }

    public StockInfo {
        history = List.copyOf(history == null ? List.of() : history);
        industry = industry == null || industry.isBlank() ? "综合" : industry;
        haltRemainingCycles = Math.max(0, haltRemainingCycles);
    }

    public double changePct() {
        if (prevClose <= 0) return 0;
        return (price - prevClose) / prevClose * 100.0;
    }
}
