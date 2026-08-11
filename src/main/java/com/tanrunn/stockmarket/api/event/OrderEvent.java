package com.tanrunn.stockmarket.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** Posted after a limit order is placed or cancelled. */
public final class OrderEvent extends Event {
    public enum Type { PLACED, CANCELLED }

    private final ServerPlayer player;
    private final Type type;
    private final long orderId;
    private final String stockId;
    private final boolean buy;
    private final long priceCents;
    private final int quantity;

    public OrderEvent(ServerPlayer player, Type type, long orderId, String stockId, boolean buy,
                      long priceCents, int quantity) {
        this.player = player;
        this.type = type;
        this.orderId = orderId;
        this.stockId = stockId;
        this.buy = buy;
        this.priceCents = priceCents;
        this.quantity = quantity;
    }

    public ServerPlayer player() { return player; }
    public Type type() { return type; }
    public long orderId() { return orderId; }
    public String stockId() { return stockId; }
    public boolean buy() { return buy; }
    public long priceCents() { return priceCents; }
    public int quantity() { return quantity; }
}
