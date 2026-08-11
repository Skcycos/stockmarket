package com.tanrunn.stockmarket.api.event;

import net.neoforged.bus.api.Event;

/** Posted after a stock's current price changes. Prices are integer cents. */
public final class PriceChangedEvent extends Event {
    private final String stockId;
    private final long oldPriceCents;
    private final long newPriceCents;

    public PriceChangedEvent(String stockId, long oldPriceCents, long newPriceCents) {
        this.stockId = stockId;
        this.oldPriceCents = oldPriceCents;
        this.newPriceCents = newPriceCents;
    }

    public String stockId() { return stockId; }
    public long oldPriceCents() { return oldPriceCents; }
    public long newPriceCents() { return newPriceCents; }
}
