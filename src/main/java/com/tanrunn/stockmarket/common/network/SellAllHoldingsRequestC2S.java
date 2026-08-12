package com.tanrunn.stockmarket.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client request to sell all currently available (not reserved) holdings. */
public record SellAllHoldingsRequestC2S() implements CustomPacketPayload {
    public static final Type<SellAllHoldingsRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("stockmarket", "sell_all_holdings_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SellAllHoldingsRequestC2S> STREAM_CODEC =
            StreamCodec.unit(new SellAllHoldingsRequestC2S());

    @Override
    public Type<SellAllHoldingsRequestC2S> type() {
        return TYPE;
    }
}
