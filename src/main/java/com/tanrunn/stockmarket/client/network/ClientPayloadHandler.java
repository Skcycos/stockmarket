package com.tanrunn.stockmarket.client.network;

import com.tanrunn.stockmarket.client.integration.MarketIntegration;
import com.tanrunn.stockmarket.common.network.MarketSnapshotC2S;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    public static void handleSnapshot(MarketSnapshotC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> MarketIntegration.onSnapshot(payload));
    }
}
