package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.common.Candle;
import com.tanrunn.stockmarket.common.StockInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Mutable market state for one stock. Definitions (name/drift/volatility) come
 * from the datapack; price state is persisted via MarketSavedData.
 */
public final class Stock {
    public static final int MAX_HISTORY = 40;

    private final String id;
    private final String name;
    private final double initialPrice;
    private final double drift;
    private final double volatility;

    private double price;
    private double dayOpen;
    private double prevClose;
    private double dayHigh;
    private double dayLow;
    private long volume;
    private final Deque<Candle> history = new ArrayDeque<>();

    public Stock(String id, String name, double initialPrice, double drift, double volatility,
                 double price, double dayOpen, double prevClose, double dayHigh, double dayLow, long volume,
                 List<Candle> history) {
        this.id = id;
        this.name = name;
        this.initialPrice = initialPrice;
        this.drift = drift;
        this.volatility = volatility;
        this.price = price;
        this.dayOpen = dayOpen;
        this.prevClose = prevClose;
        this.dayHigh = dayHigh;
        this.dayLow = dayLow;
        this.volume = volume;
        this.history.addAll(history);
        while (this.history.size() > MAX_HISTORY) {
            this.history.removeFirst();
        }
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public double initialPrice() {
        return initialPrice;
    }

    public double drift() {
        return drift;
    }

    public double volatility() {
        return volatility;
    }

    public double price() {
        return price;
    }

    public double dayOpen() {
        return dayOpen;
    }

    public double prevClose() {
        return prevClose;
    }

    public double dayHigh() {
        return dayHigh;
    }

    public double dayLow() {
        return dayLow;
    }

    public long volume() {
        return volume;
    }

    public List<Candle> history() {
        return new ArrayList<>(history);
    }

    public void setPrice(double price) {
        this.price = price;
        if (price > this.dayHigh) this.dayHigh = price;
        if (price < this.dayLow || this.dayLow == 0) this.dayLow = price;
    }

    public void addVolume(long shares) {
        this.volume += shares;
    }

    /** Day rollover: push today's candle and start a new one. */
    public void rollDay(long dayIndex) {
        history.addLast(new Candle(dayIndex, dayOpen, price, dayHigh, dayLow, volume));
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        this.prevClose = this.price;
        this.dayOpen = this.price;
        this.dayHigh = this.price;
        this.dayLow = this.price;
        this.volume = 0;
    }

    public StockInfo info() {
        return new StockInfo(id, name, price, prevClose, dayHigh, dayLow, volume, history());
    }
}
