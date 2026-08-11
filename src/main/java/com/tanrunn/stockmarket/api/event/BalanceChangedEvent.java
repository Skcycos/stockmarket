package com.tanrunn.stockmarket.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** Posted after a cross-mod deposit or withdrawal has been committed. */
public final class BalanceChangedEvent extends Event {
    public enum Type { DEPOSIT, WITHDRAWAL }

    private final ServerPlayer player;
    private final Type type;
    private final long deltaCents;
    private final long balanceCents;
    private final String transactionId;
    private final String source;
    private final String reason;

    public BalanceChangedEvent(ServerPlayer player, Type type, long deltaCents, long balanceCents,
                               String transactionId, String source, String reason) {
        this.player = player;
        this.type = type;
        this.deltaCents = deltaCents;
        this.balanceCents = balanceCents;
        this.transactionId = transactionId;
        this.source = source;
        this.reason = reason;
    }

    public ServerPlayer player() { return player; }
    public Type type() { return type; }
    public long deltaCents() { return deltaCents; }
    public long balanceCents() { return balanceCents; }
    public String transactionId() { return transactionId; }
    public String source() { return source; }
    public String reason() { return reason; }
}
