package com.tanrunn.stockmarket.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** Posted after a market trade or limit order fill is settled. */
public final class TradeEvent extends Event {
    private final ServerPlayer player;
    private final long orderId;
    private final String stockId;
    private final boolean buy;
    private final long priceCents;
    private final int quantity;
    private final long feeCents;
    private final boolean limitOrder;

    public TradeEvent(ServerPlayer player, long orderId, String stockId, boolean buy, long priceCents,
                      int quantity, long feeCents, boolean limitOrder) {
        this.player = player;
        this.orderId = orderId;
        this.stockId = stockId;
        this.buy = buy;
        this.priceCents = priceCents;
        this.quantity = quantity;
        this.feeCents = feeCents;
        this.limitOrder = limitOrder;
    }

    public ServerPlayer player() { return player; }
    public long orderId() { return orderId; }
    public String stockId() { return stockId; }
    public boolean buy() { return buy; }
    public long priceCents() { return priceCents; }
    public int quantity() { return quantity; }
    public long feeCents() { return feeCents; }
    public boolean limitOrder() { return limitOrder; }
}
