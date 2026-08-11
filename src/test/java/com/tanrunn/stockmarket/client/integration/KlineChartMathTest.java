package com.tanrunn.stockmarket.client.integration;

import com.tanrunn.stockmarket.common.Candle;
import com.tanrunn.stockmarket.common.StockInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KlineChartMathTest {
    private static Candle candle(long day, double open, double close, double high, double low, long volume) {
        return new Candle(day, open, close, high, low, volume);
    }

    @Test
    void priceRangeIsSafeForEmptyAndFlatHistory() {
        KlineChartMath.PriceRange empty = KlineChartMath.priceRange(List.of());
        assertTrue(empty.min() >= 0.01);
        assertTrue(empty.max() > empty.min());

        KlineChartMath.PriceRange flat = KlineChartMath.priceRange(List.of(candle(1, 10, 10, 10, 10, 0)));
        assertTrue(flat.min() >= 0.01);
        assertTrue(flat.max() > flat.min());
        assertTrue(flat.min() < 10);
        assertTrue(flat.max() > 10);
    }

    @Test
    void priceRangeIgnoresInvalidValuesAndKeepsPriceFloor() {
        KlineChartMath.PriceRange range = KlineChartMath.priceRange(List.of(
                candle(1, Double.NaN, 0.02, Double.POSITIVE_INFINITY, 0.02, 1),
                candle(2, 2, 1, 2.5, -3, 1)));
        assertTrue(range.min() >= 0.01);
        assertTrue(range.max() > range.min());
        assertTrue(range.max() >= 2.5);
    }

    @Test
    void movingAverageRequiresEnoughFiniteCandles() {
        List<Candle> history = List.of(
                candle(1, 1, 2, 2, 1, 10),
                candle(2, 2, 4, 4, 2, 20),
                candle(3, 4, 6, 6, 4, 30),
                candle(4, 6, 8, 8, 6, 40),
                candle(5, 8, 10, 10, 8, 50));
        assertTrue(Double.isNaN(KlineChartMath.movingAverage(history, 3, 5)));
        assertEquals(6.0, KlineChartMath.movingAverage(history, 4, 5), 0.0001);
        assertTrue(Double.isNaN(KlineChartMath.movingAverage(history, 4, 0)));
    }

    @Test
    void volumeScalingIsBounded() {
        List<Candle> history = List.of(candle(1, 1, 2, 2, 1, 0), candle(2, 2, 3, 3, 2, 100));
        assertEquals(100, KlineChartMath.maxVolume(history));
        assertEquals(0, KlineChartMath.volumeBarHeight(0, 100, 20), 0.0001);
        assertEquals(13, KlineChartMath.volumeBarHeight(100, 100, 20), 0.0001);
        assertEquals(13, KlineChartMath.volumeBarHeight(200, 100, 20), 0.0001);
        assertEquals(0, KlineChartMath.volumeBarHeight(10, 0, 20), 0.0001);
    }

    @Test
    void nearestCandleClampsAtChartEdges() {
        assertEquals(0, KlineChartMath.nearestCandleIndex(3, 0, 10, 20));
        assertEquals(1, KlineChartMath.nearestCandleIndex(3, 40, 10, 20));
        assertEquals(2, KlineChartMath.nearestCandleIndex(3, 1000, 10, 20));
        assertEquals(-1, KlineChartMath.nearestCandleIndex(0, 10, 10, 20));
    }

    @Test
    void dataSignatureIsStableAndChangesWithChartData() {
        StockInfo first = new StockInfo("aaa", "测试", 10, 9, 11, 9, 100,
                List.of(candle(1, 9, 10, 11, 8, 100)));
        StockInfo same = new StockInfo("aaa", "测试", 10, 9, 11, 9, 100,
                List.of(candle(1, 9, 10, 11, 8, 100)));
        StockInfo changed = new StockInfo("aaa", "测试", 10.1, 9, 11, 9, 100,
                List.of(candle(1, 9, 10.1, 11, 8, 100)));
        assertEquals(KlineChartMath.dataSignature(first), KlineChartMath.dataSignature(same));
        assertFalse(KlineChartMath.dataSignature(first) == KlineChartMath.dataSignature(changed));
    }
}
