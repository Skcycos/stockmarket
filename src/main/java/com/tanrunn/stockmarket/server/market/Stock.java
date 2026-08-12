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
    private double initialPrice;
    private final double drift;
    private final double volatility;
    private final String industry;

    private double price;
    private double dayOpen;
    private double prevClose;
    private double dayHigh;
    private double dayLow;
    private long volume;
    private boolean halted;
    private int haltRemainingCycles;
    private final Deque<Candle> history = new ArrayDeque<>();

    public Stock(String id, String name, double initialPrice, double drift, double volatility,
                 double price, double dayOpen, double prevClose, double dayHigh, double dayLow, long volume,
                 List<Candle> history) {
        this(id, name, initialPrice, drift, volatility, price, dayOpen, prevClose, dayHigh, dayLow, volume,
                history, "综合", false, 0);
    }

    public Stock(String id, String name, double initialPrice, double drift, double volatility,
                 double price, double dayOpen, double prevClose, double dayHigh, double dayLow, long volume,
                 List<Candle> history, String industry, boolean halted, int haltRemainingCycles) {
        this.id = id;
        this.name = name;
        this.initialPrice = initialPrice;
        this.drift = drift;
        this.volatility = volatility;
        this.industry = industry == null || industry.isBlank() ? "综合" : industry;
        this.price = price;
        this.dayOpen = dayOpen;
        this.prevClose = prevClose;
        this.dayHigh = dayHigh;
        this.dayLow = dayLow;
        this.volume = volume;
        this.halted = halted || haltRemainingCycles > 0;
        this.haltRemainingCycles = Math.max(0, haltRemainingCycles);
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

    public void setReferencePrice(double referencePrice) {
        if (Double.isFinite(referencePrice) && referencePrice > 0) this.initialPrice = referencePrice;
    }

    public double drift() {
        return drift;
    }

    public double volatility() {
        return volatility;
    }

    public String industry() {
        return industry;
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

    public boolean halted() {
        return halted;
    }

    public int haltRemainingCycles() {
        return haltRemainingCycles;
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
        this.volume += Math.max(0, shares);
    }

    public void halt(int cycles) {
        this.haltRemainingCycles = Math.max(this.haltRemainingCycles, Math.max(1, cycles));
        this.halted = true;
    }

    /** Advances the persisted halt timer at a market-cycle boundary. */
    public void advanceHaltCycle() {
        if (haltRemainingCycles > 0) haltRemainingCycles--;
        halted = haltRemainingCycles > 0;
    }

    /** Applies a forward split to price history while preserving total cost basis externally. */
    public void applySplit(int numerator, int denominator) {
        if (numerator <= 0 || denominator <= 0 || numerator == denominator) return;
        double ratio = (double) numerator / denominator;
        initialPrice = splitPrice(initialPrice, ratio);
        price = splitPrice(price, ratio);
        dayOpen = splitPrice(dayOpen, ratio);
        prevClose = splitPrice(prevClose, ratio);
        dayHigh = splitPrice(dayHigh, ratio);
        dayLow = splitPrice(dayLow, ratio);
        volume = Math.max(0, volume * (long) numerator / denominator);
        Deque<Candle> scaled = new ArrayDeque<>();
        for (Candle candle : history) {
            scaled.addLast(new Candle(candle.dayIndex(), splitPrice(candle.open(), ratio),
                    splitPrice(candle.close(), ratio), splitPrice(candle.high(), ratio),
                    splitPrice(candle.low(), ratio), Math.max(0, candle.volume() * (long) numerator / denominator)));
        }
        history.clear();
        history.addAll(scaled);
    }

    private static double splitPrice(double value, double ratio) {
        return Math.max(0.01, PriceModel.round(value / ratio));
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
        return new StockInfo(id, name, price, prevClose, dayHigh, dayLow, volume, history(), industry,
                halted, haltRemainingCycles);
    }
}
