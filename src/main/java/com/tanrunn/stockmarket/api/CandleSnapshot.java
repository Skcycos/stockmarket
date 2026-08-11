package com.tanrunn.stockmarket.api;

/** Immutable public OHLCV candle. Prices are integer cents. */
public record CandleSnapshot(
        long dayIndex,
        long openCents,
        long closeCents,
        long highCents,
        long lowCents,
        long volume) {
}
