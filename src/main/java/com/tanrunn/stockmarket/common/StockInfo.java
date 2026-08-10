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
        List<Candle> history) {

    public double changePct() {
        if (prevClose <= 0) return 0;
        return (price - prevClose) / prevClose * 100.0;
    }
}
