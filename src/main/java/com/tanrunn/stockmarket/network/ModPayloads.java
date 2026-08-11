package com.tanrunn.stockmarket.network;

import com.tanrunn.stockmarket.client.network.ClientPayloadHandler;
import com.tanrunn.stockmarket.common.network.CancelOrderRequestC2S;
import com.tanrunn.stockmarket.common.network.LimitOrderRequestC2S;
import com.tanrunn.stockmarket.common.network.MarketRequestC2S;
import com.tanrunn.stockmarket.common.network.MarketSnapshotC2S;
import com.tanrunn.stockmarket.common.network.TradeRequestC2S;
import com.tanrunn.stockmarket.server.network.ServerPayloadHandler;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModPayloads {
    private ModPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(MarketSnapshotC2S.TYPE, MarketSnapshotC2S.STREAM_CODEC, ClientPayloadHandler::handleSnapshot);
        registrar.playToServer(MarketRequestC2S.TYPE, MarketRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(TradeRequestC2S.TYPE, TradeRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(LimitOrderRequestC2S.TYPE, LimitOrderRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(CancelOrderRequestC2S.TYPE, CancelOrderRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
    }
}
