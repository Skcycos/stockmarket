package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.Config;
import com.tanrunn.stockmarket.common.Candle;
import com.tanrunn.stockmarket.common.MarketNews;
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
                             long volume, List<Candle> history, boolean halted, int haltRemainingCycles,
                             double referencePrice) {
        public StockState(double price, double dayOpen, double prevClose, double dayHigh, double dayLow,
                          long volume, List<Candle> history) {
            this(price, dayOpen, prevClose, dayHigh, dayLow, volume, history, false, 0, 0);
        }

        public StockState(double price, double dayOpen, double prevClose, double dayHigh, double dayLow,
                          long volume, List<Candle> history, boolean halted, int haltRemainingCycles) {
            this(price, dayOpen, prevClose, dayHigh, dayLow, volume, history, halted, haltRemainingCycles, 0);
        }
    }

    /** Persisted world event; player accounts consume it exactly once. */
    public record CorporateAction(long id, long dayIndex, String stockId, String type,
                                  int numerator, int denominator, double dividendPerShare) {
    }

    private final Map<String, StockState> states = new HashMap<>();
    private final OrderBook orderBook = new OrderBook();
    private final List<CorporateAction> corporateActions = new ArrayList<>();
    private final List<MarketNews> news = new ArrayList<>();
    private long nextCorporateActionId = 1;
    private long nextNewsId = 1;

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
                    history,
                    entry.getBoolean("halted"),
                    entry.getInt("haltRemainingCycles"),
                    entry.contains("referencePrice", Tag.TAG_ANY_NUMERIC) ? entry.getDouble("referencePrice") : 0));
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
            // Older saves did not persist the exact buy reservation. Recover a
            // best-effort amount for those orders; new saves never need to
            // recompute it after a split.
            double reservedCash = order.contains("reservedCash", Tag.TAG_ANY_NUMERIC)
                    ? order.getDouble("reservedCash")
                    : (buy ? TradeEngine.buyReservation(price, qty, Config.FEE_RATE.get()) : 0);
            if (order.contains("id", Tag.TAG_ANY_NUMERIC)) {
                // 用原始 ID 恢复订单，保证重启后委托 ID 不变；
                // restore() 不会触发 dirty（恢复是加载存档，不是新增委托）。
                data.orderBook.restore(order.getLong("id"), player, stock, buy, price, qty,
                        reservedCostBasis, reservedCash);
            } else {
                // 旧存档（无 id 字段）：按原逻辑分配 ID，同样走 restore 避免误标 dirty
                data.orderBook.restore(data.orderBook.nextId(), player, stock, buy, price, qty,
                        reservedCostBasis, reservedCash);
            }
        }
        ListTag actions = tag.getList("corporateActions", Tag.TAG_COMPOUND);
        for (int i = 0; i < actions.size(); i++) {
            CompoundTag action = actions.getCompound(i);
            long id = action.getLong("id");
            data.corporateActions.add(new CorporateAction(id, action.getLong("day"), action.getString("stock"),
                    action.getString("type"), action.getInt("numerator"), action.getInt("denominator"),
                    action.getDouble("dividendPerShare")));
            data.nextCorporateActionId = Math.max(data.nextCorporateActionId, id + 1);
        }
        ListTag news = tag.getList("news", Tag.TAG_COMPOUND);
        for (int i = 0; i < news.size(); i++) {
            CompoundTag item = news.getCompound(i);
            long id = item.getLong("id");
            data.news.add(new MarketNews(id, item.getLong("day"), item.getString("stock"),
                    item.getString("industry"), item.getString("type"), item.getString("title"),
                    item.getString("detail"), item.getDouble("impactPct")));
            data.nextNewsId = Math.max(data.nextNewsId, id + 1);
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
            entry.putBoolean("halted", state.halted());
            entry.putInt("haltRemainingCycles", state.haltRemainingCycles());
            if (state.referencePrice() > 0) entry.putDouble("referencePrice", state.referencePrice());
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
            if (order.buy() && order.reservedCash() > 0) {
                o.putDouble("reservedCash", order.reservedCash());
            }
            orders.add(o);
        }
        tag.put("orders", orders);
        ListTag actions = new ListTag();
        for (CorporateAction action : corporateActions) {
            CompoundTag a = new CompoundTag();
            a.putLong("id", action.id());
            a.putLong("day", action.dayIndex());
            a.putString("stock", action.stockId());
            a.putString("type", action.type());
            a.putInt("numerator", action.numerator());
            a.putInt("denominator", action.denominator());
            a.putDouble("dividendPerShare", action.dividendPerShare());
            actions.add(a);
        }
        tag.put("corporateActions", actions);
        ListTag news = new ListTag();
        for (MarketNews item : this.news) {
            CompoundTag n = new CompoundTag();
            n.putLong("id", item.id());
            n.putLong("day", item.dayIndex());
            n.putString("stock", item.stockId());
            n.putString("industry", item.industry());
            n.putString("type", item.type());
            n.putString("title", item.title());
            n.putString("detail", item.detail());
            n.putDouble("impactPct", item.impactPct());
            news.add(n);
        }
        tag.put("news", news);
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

    public List<CorporateAction> corporateActions() {
        return List.copyOf(corporateActions);
    }

    public long latestCorporateActionId() {
        return nextCorporateActionId - 1;
    }

    public CorporateAction addCorporateAction(long dayIndex, String stockId, String type,
                                              int numerator, int denominator, double dividendPerShare) {
        CorporateAction action = new CorporateAction(nextCorporateActionId++, dayIndex, stockId, type,
                numerator, denominator, dividendPerShare);
        corporateActions.add(action);
        setDirty();
        return action;
    }

    public List<MarketNews> news() {
        return List.copyOf(news);
    }

    public MarketNews addNews(long dayIndex, String stockId, String industry, String type,
                              String title, String detail, double impactPct) {
        MarketNews item = new MarketNews(nextNewsId++, dayIndex, stockId, industry, type, title, detail, impactPct);
        news.add(0, item);
        while (news.size() > 40) news.remove(news.size() - 1);
        setDirty();
        return item;
    }
}
