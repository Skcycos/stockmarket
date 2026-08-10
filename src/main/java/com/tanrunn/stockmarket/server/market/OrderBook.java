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
 */
public final class OrderBook {
    public record Order(long id, UUID player, String stockId, boolean buy, double price, int quantity) {
    }

    public interface MatchSink {
        void onFill(Order order, double fillPrice);
    }

    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private long nextId = 1;

    public long nextId() {
        return nextId;
    }

    public void restoreNextId(long id) {
        this.nextId = Math.max(this.nextId, id);
    }

    public long place(UUID player, String stockId, boolean buy, double price, int quantity) {
        Order order = new Order(nextId++, player, stockId, buy, price, quantity);
        orders.put(order.id(), order);
        return order.id();
    }

    public Order cancel(long id) {
        return orders.remove(id);
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

    /** Matches all eligible orders of one stock at the given market price. */
    public void match(String stockId, double marketPrice, MatchSink sink) {
        List<Long> filled = new ArrayList<>();
        for (Order order : orders.values()) {
            if (!order.stockId().equals(stockId)) continue;
            boolean hits = order.buy() ? marketPrice <= order.price() : marketPrice >= order.price();
            if (!hits) continue;
            sink.onFill(order, order.price());
            filled.add(order.id());
        }
        filled.forEach(orders::remove);
    }

    public void clear() {
        orders.clear();
    }
}
