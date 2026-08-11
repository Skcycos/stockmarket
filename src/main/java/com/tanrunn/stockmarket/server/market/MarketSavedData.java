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
import java.util.UUID;

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

    public MarketSavedData() {
        // 委托簿任何变化（新增/成交/撤单/清理）都立即标记 SavedData dirty，
        // 保证在正常世界存档周期内落盘，而不只依赖服务器停止时的 save()。
        orderBook.setDirtyHandler(this::setDirty);
    }

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
            UUID player = UUID.fromString(order.getString("player"));
            String stock = order.getString("stock");
            boolean buy = order.getBoolean("buy");
            double price = order.getDouble("price");
            int qty = order.getInt("qty");
            double reservedCostBasis = order.getDouble("reservedCostBasis");
            if (order.contains("id", Tag.TAG_ANY_NUMERIC)) {
                // 用原始 ID 恢复订单，保证重启后委托 ID 不变；
                // restore() 不会触发 dirty（恢复是加载存档，不是新增委托）。
                data.orderBook.restore(order.getLong("id"), player, stock, buy, price, qty, reservedCostBasis);
            } else {
                // 旧存档（无 id 字段）：按原逻辑分配 ID，同样走 restore 避免误标 dirty
                data.orderBook.restore(data.orderBook.nextId(), player, stock, buy, price, qty, reservedCostBasis);
            }
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
            if (order.reservedCostBasis() > 0) {
                o.putDouble("reservedCostBasis", order.reservedCostBasis());
            }
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
