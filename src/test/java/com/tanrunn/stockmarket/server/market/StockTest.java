package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.common.Candle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockTest {

    @Test
    void rollDayPushesCompletedCandleAndResets() {
        Stock stock = new Stock("t", "测试", 10, 0, 0.02, 12, 10, 9.5, 13, 9, 100, List.of());
        stock.setPrice(14);   // updates dayHigh to 14
        stock.setPrice(8.5);  // updates dayLow to 8.5
        stock.addVolume(50);
        stock.rollDay(100);

        List<Candle> history = stock.history();
        assertEquals(1, history.size());
        Candle candle = history.get(0);
        assertEquals(100, candle.dayIndex());
        assertEquals(10.0, candle.open(), 0.001);
        assertEquals(8.5, candle.close(), 0.001);
        assertEquals(14.0, candle.high(), 0.001);
        assertEquals(8.5, candle.low(), 0.001);
        assertEquals(150, candle.volume());

        // new day starts at the close
        assertEquals(8.5, stock.prevClose(), 0.001);
        assertEquals(8.5, stock.dayOpen(), 0.001);
        assertEquals(8.5, stock.dayHigh(), 0.001);
        assertEquals(8.5, stock.dayLow(), 0.001);
        assertEquals(0, stock.volume());
    }

    @Test
    void historyIsCappedAtForty() {
        Stock stock = new Stock("t", "测试", 10, 0, 0.02, 10, 10, 10, 10, 10, 0, List.of());
        for (int day = 0; day < 60; day++) {
            stock.setPrice(10 + day * 0.1);
            stock.rollDay(day);
        }
        assertEquals(40, stock.history().size());
        assertEquals(59, stock.history().get(39).dayIndex(), "oldest kept candle is the most recent 40");
    }

    @Test
    void splitScalesPriceHistoryAndLeavesReferenceRatioStable() {
        Stock stock = new Stock("t", "测试", 20, 0, 0.02, 20, 20, 20, 22, 18, 100,
                List.of(new Candle(1, 18, 20, 22, 17, 100)), "科技", false, 0);
        stock.applySplit(2, 1);
        assertEquals(10.0, stock.initialPrice(), 0.001);
        assertEquals(10.0, stock.price(), 0.001);
        assertEquals(11.0, stock.dayHigh(), 0.001);
        assertEquals(200, stock.volume());
        assertEquals(9.0, stock.history().get(0).open(), 0.001);
        assertEquals(200, stock.history().get(0).volume());
    }

    @Test
    void haltTimerResumesAfterConfiguredCycles() {
        Stock stock = new Stock("t", "测试", 10, 0, 0.02, 10, 10, 10, 10, 10, 0, List.of());
        stock.halt(2);
        assertTrue(stock.halted());
        stock.advanceHaltCycle();
        assertTrue(stock.halted());
        stock.advanceHaltCycle();
        assertFalse(stock.halted());
    }
}
