package com.tanrunn.stockmarket.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TradeRequestC2S(
        String stockId,
        int quantity,
        boolean buy) implements CustomPacketPayload {

    public static final Type<TradeRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("stockmarket", "trade_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeRequestC2S> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, TradeRequestC2S::stockId,
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT, TradeRequestC2S::quantity,
            net.minecraft.network.codec.ByteBufCodecs.BOOL, TradeRequestC2S::buy,
            TradeRequestC2S::new);

    @Override
    public Type<TradeRequestC2S> type() {
        return TYPE;
    }
}
