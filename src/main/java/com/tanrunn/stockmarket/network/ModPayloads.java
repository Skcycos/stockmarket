package com.tanrunn.stockmarket.network;

import com.tanrunn.stockmarket.client.network.ClientPayloadHandler;
import com.tanrunn.stockmarket.common.network.BankTransferRequestC2S;
import com.tanrunn.stockmarket.common.network.CancelOrderRequestC2S;
import com.tanrunn.stockmarket.common.network.CancelAllOrdersRequestC2S;
import com.tanrunn.stockmarket.common.network.LimitOrderRequestC2S;
import com.tanrunn.stockmarket.common.network.MarketRequestC2S;
import com.tanrunn.stockmarket.common.network.MarketSnapshotC2S;
import com.tanrunn.stockmarket.common.network.TradeRequestC2S;
import com.tanrunn.stockmarket.common.network.SellAllHoldingsRequestC2S;
import com.tanrunn.stockmarket.server.network.ServerPayloadHandler;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModPayloads {
    private ModPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // Bump the channel protocol whenever a payload codec changes.
        // Protocol "4": bank-transfer C2S now carries direction-specific amounts
        // (requested copper for deposit / requested securities cents for withdraw).
        PayloadRegistrar registrar = event.registrar("4");
        registrar.playToClient(MarketSnapshotC2S.TYPE, MarketSnapshotC2S.STREAM_CODEC, ClientPayloadHandler::handleSnapshot);
        registrar.playToServer(MarketRequestC2S.TYPE, MarketRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(TradeRequestC2S.TYPE, TradeRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(LimitOrderRequestC2S.TYPE, LimitOrderRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(CancelOrderRequestC2S.TYPE, CancelOrderRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(CancelAllOrdersRequestC2S.TYPE, CancelAllOrdersRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(SellAllHoldingsRequestC2S.TYPE, SellAllHoldingsRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(BankTransferRequestC2S.TYPE, BankTransferRequestC2S.STREAM_CODEC, ServerPayloadHandler::handle);
    }
}
