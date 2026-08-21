package com.tanrunn.stockmarket.server.market;

/** Pure daily P&amp;L calculation that excludes external account funding flows. */
final class DailyPnlCalculator {
    private DailyPnlCalculator() {
    }

    static double calculate(double totalValue, double baselineValue, long externalFlowCents) {
        double externalFlow = externalFlowCents / 100.0;
        return round2(totalValue - baselineValue - externalFlow);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
