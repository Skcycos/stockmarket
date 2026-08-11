package com.tanrunn.stockmarket.client.integration;

import com.tanrunn.stockmarket.common.Candle;
import com.tanrunn.stockmarket.common.StockInfo;

import java.util.List;

/**
 * Pure calculations used by the K-line renderer.
 *
 * <p>Keeping these calculations independent from Canvas/AUI makes edge cases
 * easy to test and prevents rendering code from becoming a second source of
 * trading-data rules.</p>
 */
public final class KlineChartMath {
    private static final double MIN_PRICE = 0.01;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private KlineChartMath() {
    }

    public record PriceRange(double min, double max) {
    }

    /**
     * Returns a padded finite price range. Invalid candles are ignored; an
     * empty/invalid history still gets a safe range for the first paint.
     */
    public static PriceRange priceRange(List<Candle> history) {
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        if (history != null) {
            for (Candle candle : history) {
                if (candle == null) continue;
                low = finiteMin(low, candle.low());
                low = finiteMin(low, candle.open());
                low = finiteMin(low, candle.close());
                high = finiteMax(high, candle.high());
                high = finiteMax(high, candle.open());
                high = finiteMax(high, candle.close());
            }
        }
        if (!Double.isFinite(low) || !Double.isFinite(high)) {
            return new PriceRange(MIN_PRICE, 1.0);
        }

        double safeHigh = Math.max(MIN_PRICE, high);
        double range = Math.max(high - low, Math.max(MIN_PRICE, safeHigh * 0.01));
        double min = Math.max(MIN_PRICE, low - range * 0.08);
        double max = Math.max(min + MIN_PRICE, high + range * 0.08);
        return new PriceRange(min, max);
    }

    public static double movingAverage(List<Candle> history, int index, int period) {
        if (history == null || period <= 0 || index < period - 1 || index >= history.size()) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = index - period + 1; i <= index; i++) {
            Candle candle = history.get(i);
            if (candle == null || !Double.isFinite(candle.close())) return Double.NaN;
            sum += candle.close();
        }
        return sum / period;
    }

    public static long maxVolume(List<Candle> history) {
        long max = 1;
        if (history == null) return max;
        for (Candle candle : history) {
            if (candle != null) max = Math.max(max, Math.max(0, candle.volume()));
        }
        return max;
    }

    public static double volumeBarHeight(long volume, long maximum, double volumeHeight) {
        if (maximum <= 0 || !Double.isFinite(volumeHeight) || volumeHeight <= 0) return 0;
        double ratio = Math.max(0, Math.min(1, volume / (double) maximum));
        return ratio * Math.max(0, volumeHeight - 7);
    }

    /** Returns the candle nearest to a logical x coordinate, clamped to bounds. */
    public static int nearestCandleIndex(int count, double x, double left, double slot) {
        if (count <= 0 || !Double.isFinite(x) || !Double.isFinite(left) || !Double.isFinite(slot) || slot <= 0) {
            return -1;
        }
        int index = (int) Math.round((x - left - slot / 2) / slot);
        return Math.max(0, Math.min(count - 1, index));
    }

    /** Stable content signature used to skip identical Canvas paints. */
    public static long dataSignature(StockInfo stock) {
        if (stock == null) return 0;
        long hash = FNV_OFFSET;
        hash = mix(hash, stock.id());
        hash = mix(hash, stock.price());
        hash = mix(hash, stock.prevClose());
        hash = mix(hash, stock.dayHigh());
        hash = mix(hash, stock.dayLow());
        hash = mix(hash, stock.volume());
        List<Candle> history = stock.history();
        if (history == null) return hash;
        hash = mix(hash, history.size());
        for (Candle candle : history) {
            if (candle == null) {
                hash = mix(hash, 0);
                continue;
            }
            hash = mix(hash, candle.dayIndex());
            hash = mix(hash, candle.open());
            hash = mix(hash, candle.close());
            hash = mix(hash, candle.high());
            hash = mix(hash, candle.low());
            hash = mix(hash, candle.volume());
        }
        return hash;
    }

    private static double finiteMin(double current, double candidate) {
        return Double.isFinite(candidate) ? Math.min(current, candidate) : current;
    }

    private static double finiteMax(double current, double candidate) {
        return Double.isFinite(candidate) ? Math.max(current, candidate) : current;
    }

    private static long mix(long hash, String value) {
        if (value == null) return mix(hash, 0);
        for (int i = 0; i < value.length(); i++) hash = mix(hash, value.charAt(i));
        return mix(hash, value.length());
    }

    private static long mix(long hash, double value) {
        return mix(hash, Double.doubleToLongBits(value));
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * FNV_PRIME;
    }
}
