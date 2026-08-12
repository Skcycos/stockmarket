package com.tanrunn.stockmarket.server.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Limit order book. Whole-fill matching: a buy order fills when the market price
 * drops to/below its limit, a sell order when the price rises to/above its limit.
 * Fills execute at the order's limit price. Side effects (account transfers,
 * notifications) are delegated to a MatchSink so the matching logic stays pure.
 *
 * <p>离线结算策略：订单只有在 sink 确认成交（返回 true）后才会从簿上移除。
 * 玩家离线时 sink 返回 false，订单继续保留，其预留资金/持仓始终留在玩家账上；
 * 玩家重新上线后，后续撮合轮次会再次尝试成交（或由玩家主动撤单退款）。
 * 因此不会出现"订单已删除、但资金或持仓无人处理"的情况。
 */
public final class OrderBook {
    public record Order(long id, UUID player, String stockId, boolean buy, double price, int quantity,
                        double reservedCostBasis) {
        public Order(long id, UUID player, String stockId, boolean buy, double price, int quantity) {
            this(id, player, stockId, buy, price, quantity, 0);
        }
    }

    public interface MatchSink {
        /**
         * Called when an order hits the market price. Return {@code true} to
         * confirm the fill and remove the order; return {@code false} to keep the
         * order in the book (e.g. the player is offline and settlement is deferred).
         */
        boolean onFill(Order order, double fillPrice);
    }

    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private long nextId = 1;
    private Runnable dirtyHandler = () -> {};

    /**
     * Registers a callback fired whenever the book changes (place / fill / cancel /
     * clear). MarketSavedData wires this to {@code setDirty()} so委托簿的任何变化
     * 都会及时落盘，而不是只依赖服务器停止时的 save()。
     */
    public void setDirtyHandler(Runnable dirtyHandler) {
        this.dirtyHandler = dirtyHandler;
    }

    public long nextId() {
        return nextId;
    }

    public void restoreNextId(long id) {
        this.nextId = Math.max(this.nextId, id);
    }

    /** Places a new order, assigning the next free id. */
    public long place(UUID player, String stockId, boolean buy, double price, int quantity) {
        return place(player, stockId, buy, price, quantity, 0);
    }

    /** Places an order with the cost basis reserved for a sell order. */
    public long place(UUID player, String stockId, boolean buy, double price, int quantity,
                      double reservedCostBasis) {
        long id = nextId++;
        orders.put(id, new Order(id, player, stockId, buy, price, quantity, reservedCostBasis));
        dirtyHandler.run();
        return id;
    }

    /**
     * Restores a persisted order with its original id (used by
     * MarketSavedData.read so outstanding order ids survive a restart).
     * 恢复是"加载存档"而非"新增委托"，因此不触发 dirty 标记；
     * 自增计数器仍会推进到超过恢复出的最大 id。
     */
    public long restore(long id, UUID player, String stockId, boolean buy, double price, int quantity) {
        return restore(id, player, stockId, buy, price, quantity, 0);
    }

    public long restore(long id, UUID player, String stockId, boolean buy, double price, int quantity,
                        double reservedCostBasis) {
        orders.put(id, new Order(id, player, stockId, buy, price, quantity, reservedCostBasis));
        nextId = Math.max(nextId, id + 1);
        return id;
    }

    public Order cancel(long id) {
        Order removed = orders.remove(id);
        if (removed != null) {
            dirtyHandler.run();
        }
        return removed;
    }

    public Order get(long id) {
        return orders.get(id);
    }

    public List<Order> ordersOf(UUID player) {
        List<Order> result = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.player().equals(player)) {
                result.add(order);
            }
        }
        return result;
    }

    public List<Order> all() {
        return new ArrayList<>(orders.values());
    }

    public int size() {
        return orders.size();
    }

    /** Reprices and resizes outstanding orders for a forward stock split. */
    public void applySplit(String stockId, int numerator, int denominator) {
        if (stockId == null || numerator <= 0 || denominator <= 0 || numerator == denominator) return;
        double ratio = (double) numerator / denominator;
        boolean changed = false;
        for (Map.Entry<Long, Order> entry : new ArrayList<>(orders.entrySet())) {
            Order order = entry.getValue();
            if (!stockId.equals(order.stockId())) continue;
            long scaled = (long) order.quantity() * numerator / denominator;
            if (scaled <= 0 || scaled > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("split order quantity out of range");
            }
            entry.setValue(new Order(order.id(), order.player(), order.stockId(), order.buy(),
                    Math.max(0.01, PriceModel.round(order.price() / ratio)), (int) scaled,
                    order.reservedCostBasis()));
            changed = true;
        }
        if (changed) dirtyHandler.run();
    }

    /** Matches all eligible orders of one stock at the given market price. */
    public void match(String stockId, double marketPrice, MatchSink sink) {
        List<Long> filled = new ArrayList<>();
        for (Order order : orders.values()) {
            if (!order.stockId().equals(stockId)) continue;
            boolean hits = order.buy() ? marketPrice <= order.price() : marketPrice >= order.price();
            if (!hits) continue;
            if (sink.onFill(order, order.price())) {
                filled.add(order.id());
            }
        }
        if (!filled.isEmpty()) {
            filled.forEach(orders::remove);
            dirtyHandler.run();
        }
    }

    public void clear() {
        if (!orders.isEmpty()) {
            orders.clear();
            dirtyHandler.run();
        }
    }
}
