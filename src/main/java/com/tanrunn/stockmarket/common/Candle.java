package com.tanrunn.stockmarket.common;

public record Candle(
        long dayIndex,
        double open,
        double close,
        double high,
        double low,
        long volume) {
}
