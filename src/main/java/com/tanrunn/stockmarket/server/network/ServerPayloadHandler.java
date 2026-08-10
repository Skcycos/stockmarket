package com.tanrunn.stockmarket.server.network;

import com.tanrunn.stockmarket.Config;
import com.tanrunn.stockmarket.common.network.MarketRequestC2S;
import com.tanrunn.stockmarket.common.network.TradeRequestC2S;
import com.tanrunn.stockmarket.server.market.MarketService;
import com.tanrunn.stockmarket.server.market.TradeEngine;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ServerPayloadHandler {
    private ServerPayloadHandler() {
    }

    public static void handle(MarketRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer player
                    && MarketService.get() != null) {
                MarketService service = MarketService.get();
                if (payload.openPanel()) {
                    service.addViewer(player.getUUID());
                } else if (payload.closePanel()) {
                    service.removeViewer(player.getUUID());
                }
                service.sendSnapshot(player, payload.openPanel(), null);
            }
        });
    }

    public static void handle(TradeRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer player
                    && MarketService.get() != null) {
                if (!Config.ENABLED.get()) {
                    MarketService.get().sendSnapshot(player, false, "股市已关闭");
                    return;
                }
                int quantity = Math.max(1, Math.min(payload.quantity(), Config.MAX_ORDER_QTY.get()));
                TradeEngine.Result result = MarketService.get().trade(player, payload.stockId(), quantity, payload.buy());
                MarketService.get().sendSnapshot(player, false, result.message());
            }
        });
    }
}
