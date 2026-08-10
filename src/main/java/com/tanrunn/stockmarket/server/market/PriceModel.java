package com.tanrunn.stockmarket.server.market;

import java.util.Random;

/**
 * Pure geometric-random-walk price model. Same seed sequence reproduces the
 * same prices, so it is fully unit-testable.
 */
public final class PriceModel {
    private PriceModel() {
    }

    public static double nextPrice(double price, double drift, double volatility, Random random) {
        double noise = random.nextGaussian();
        double next = price * Math.exp(drift + volatility * noise);
        if (next < 0.01) next = 0.01;
        return round(next);
    }

    public static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
