package com.tanrunn.stockmarket.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client request to cancel every outstanding order owned by the sender. */
public record CancelAllOrdersRequestC2S() implements CustomPacketPayload {
    public static final Type<CancelAllOrdersRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("stockmarket", "cancel_all_orders_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CancelAllOrdersRequestC2S> STREAM_CODEC =
            StreamCodec.unit(new CancelAllOrdersRequestC2S());

    @Override
    public Type<CancelAllOrdersRequestC2S> type() {
        return TYPE;
    }
}
