package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.common.Candle;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists per-stock price state and the daily candle history across server
 * restarts (world SavedData).
 */
public class MarketSavedData extends SavedData {
    public record StockState(double price, double dayOpen, double prevClose, double dayHigh, double dayLow,
                             long volume, List<Candle> history) {
    }

    private final Map<String, StockState> states = new HashMap<>();
    private final OrderBook orderBook = new OrderBook();

    public static MarketSavedData load(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MarketSavedData::new, MarketSavedData::read), "stockmarket");
    }

    private static MarketSavedData read(CompoundTag tag, HolderLookup.Provider provider) {
        MarketSavedData data = new MarketSavedData();
        ListTag list = tag.getList("stocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            List<Candle> history = new ArrayList<>();
            ListTag candles = entry.getList("history", Tag.TAG_COMPOUND);
            for (int j = 0; j < candles.size(); j++) {
                CompoundTag candle = candles.getCompound(j);
                history.add(new Candle(candle.getLong("day"), candle.getDouble("open"), candle.getDouble("close"),
                        candle.getDouble("high"), candle.getDouble("low"), candle.getLong("vol")));
            }
            data.states.put(entry.getString("id"), new StockState(
                    entry.getDouble("price"),
                    entry.getDouble("dayOpen"),
                    entry.getDouble("prevClose"),
                    entry.getDouble("dayHigh"),
                    entry.getDouble("dayLow"),
                    entry.getLong("volume"),
                    history));
        }
        data.orderBook.restoreNextId(tag.getLong("nextOrderId"));
        ListTag orders = tag.getList("orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < orders.size(); i++) {
            CompoundTag order = orders.getCompound(i);
            data.orderBook.place(
                    java.util.UUID.fromString(order.getString("player")),
                    order.getString("stock"),
                    order.getBoolean("buy"),
                    order.getDouble("price"),
                    order.getInt("qty"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        states.forEach((id, state) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id);
            entry.putDouble("price", state.price());
            entry.putDouble("dayOpen", state.dayOpen());
            entry.putDouble("prevClose", state.prevClose());
            entry.putDouble("dayHigh", state.dayHigh());
            entry.putDouble("dayLow", state.dayLow());
            entry.putLong("volume", state.volume());
            ListTag candles = new ListTag();
            for (Candle candle : state.history()) {
                CompoundTag c = new CompoundTag();
                c.putLong("day", candle.dayIndex());
                c.putDouble("open", candle.open());
                c.putDouble("close", candle.close());
                c.putDouble("high", candle.high());
                c.putDouble("low", candle.low());
                c.putLong("vol", candle.volume());
                candles.add(c);
            }
            entry.put("history", candles);
            list.add(entry);
        });
        tag.put("stocks", list);
        tag.putLong("nextOrderId", orderBook.nextId());
        ListTag orders = new ListTag();
        for (OrderBook.Order order : orderBook.all()) {
            CompoundTag o = new CompoundTag();
            o.putLong("id", order.id());
            o.putString("player", order.player().toString());
            o.putString("stock", order.stockId());
            o.putBoolean("buy", order.buy());
            o.putDouble("price", order.price());
            o.putInt("qty", order.quantity());
            orders.add(o);
        }
        tag.put("orders", orders);
        return tag;
    }

    public OrderBook orderBook() {
        return orderBook;
    }

    public StockState get(String id) {
        return states.get(id);
    }

    public void put(String id, StockState state) {
        states.put(id, state);
        setDirty();
    }
}
