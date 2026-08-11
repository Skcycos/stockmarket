package com.tanrunn.stockmarket.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * Optional adapter implemented by an economy Mod. The adapter owns its own
 * currency and uses integer cents as the exchange unit. Implementations must
 * only be called on the server thread and should be idempotent where possible.
 */
public interface CurrencyBridge {
    String id();

    long balanceCents(ServerPlayer player);

    boolean withdrawCents(ServerPlayer player, long cents, String reason);

    boolean depositCents(ServerPlayer player, long cents, String reason);
}
