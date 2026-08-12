package com.tanrunn.stockmarket.server.network;

import com.tanrunn.stockmarket.common.network.CancelOrderRequestC2S;
import com.tanrunn.stockmarket.common.network.CancelAllOrdersRequestC2S;
import com.tanrunn.stockmarket.common.network.LimitOrderRequestC2S;
import com.tanrunn.stockmarket.common.network.MarketRequestC2S;
import com.tanrunn.stockmarket.common.network.TradeRequestC2S;
import com.tanrunn.stockmarket.common.network.SellAllHoldingsRequestC2S;
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
                // 数量/开关校验统一在 MarketService 入口门禁执行（服务端权威），
                // 这里不再钳制数量，让非法请求以明确提示失败。
                TradeEngine.Result result = MarketService.get().trade(player, payload.stockId(), payload.quantity(), payload.buy());
                MarketService.get().sendSnapshot(player, false, result.message());
            }
        });
    }

    public static void handle(LimitOrderRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer player
                    && MarketService.get() != null) {
                // 价格、数量、股票 ID 全部由服务端再次校验
                // （MarketService.placeOrder：股票存在、数量>0、数量≤MAX_ORDER_QTY、
                //  ENABLED=true、价格≥0.01、资金/持仓足够）。
                TradeEngine.Result result = MarketService.get()
                        .placeOrder(player, payload.stockId(), payload.buy(), payload.price(), payload.quantity());
                MarketService.get().sendSnapshot(player, false, result.message());
            }
        });
    }

    public static void handle(CancelOrderRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer player
                    && MarketService.get() != null) {
                // MarketService.cancelOrder 校验订单存在且属于该玩家；
                // 股市关闭期间也允许撤单退款（资金回收不锁死）。
                TradeEngine.Result result = MarketService.get().cancelOrder(player, payload.orderId());
                MarketService.get().sendSnapshot(player, false, result.message());
            }
        });
    }

    public static void handle(CancelAllOrdersRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer player
                    && MarketService.get() != null) {
                TradeEngine.Result result = MarketService.get().cancelAllOrders(player);
                MarketService.get().sendSnapshot(player, false, result.message());
            }
        });
    }

    public static void handle(SellAllHoldingsRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer player
                    && MarketService.get() != null) {
                TradeEngine.Result result = MarketService.get().sellAllHoldings(player);
                MarketService.get().sendSnapshot(player, false, result.message());
            }
        });
    }
}
