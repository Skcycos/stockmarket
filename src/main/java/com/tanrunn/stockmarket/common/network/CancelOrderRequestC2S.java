package com.tanrunn.stockmarket.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server cancel-order request. The server checks ownership before
 * cancelling, so a player can only cancel their own orders.
 */
public record CancelOrderRequestC2S(long orderId) implements CustomPacketPayload {

    public static final Type<CancelOrderRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("stockmarket", "cancel_order_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CancelOrderRequestC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, CancelOrderRequestC2S::orderId,
            CancelOrderRequestC2S::new);

    @Override
    public Type<CancelOrderRequestC2S> type() {
        return TYPE;
    }
}
