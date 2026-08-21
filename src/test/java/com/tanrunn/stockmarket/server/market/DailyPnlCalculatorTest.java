package com.tanrunn.stockmarket.server.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyPnlCalculatorTest {
    @Test
    void depositDoesNotCountAsDailyProfit() {
        assertEquals(0.0, DailyPnlCalculator.calculate(1_100.0, 1_000.0, 10_000), 0.0001);
    }

    @Test
    void withdrawalDoesNotCountAsDailyLoss() {
        assertEquals(0.0, DailyPnlCalculator.calculate(900.0, 1_000.0, -10_000), 0.0001);
    }

    @Test
    void marketPerformanceRemainsAfterExternalDeposit() {
        assertEquals(20.0, DailyPnlCalculator.calculate(1_120.0, 1_000.0, 10_000), 0.0001);
    }
}
