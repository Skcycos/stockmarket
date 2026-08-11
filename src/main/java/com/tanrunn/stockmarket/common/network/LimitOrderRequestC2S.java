package com.tanrunn.stockmarket.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server limit order request.
 * All values (stock, price, quantity, direction) are re-validated on the server;
 * nothing from the client is trusted.
 */
public record LimitOrderRequestC2S(
        String stockId,
        boolean buy,
        double price,
        int quantity) implements CustomPacketPayload {

    public static final Type<LimitOrderRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("stockmarket", "limit_order_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LimitOrderRequestC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LimitOrderRequestC2S::stockId,
            ByteBufCodecs.BOOL, LimitOrderRequestC2S::buy,
            ByteBufCodecs.DOUBLE, LimitOrderRequestC2S::price,
            ByteBufCodecs.VAR_INT, LimitOrderRequestC2S::quantity,
            LimitOrderRequestC2S::new);

    @Override
    public Type<LimitOrderRequestC2S> type() {
        return TYPE;
    }
}
