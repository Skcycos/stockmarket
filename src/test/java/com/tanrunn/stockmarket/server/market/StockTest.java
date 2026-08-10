package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.common.Candle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
