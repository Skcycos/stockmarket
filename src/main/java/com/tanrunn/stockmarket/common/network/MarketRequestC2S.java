package com.tanrunn.stockmarket.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server market panel control.
 * `openPanel` = open the AUI screen and start receiving pushes;
 * `closePanel` = stop receiving pushes (screen closed);
 * neither = plain refresh request.
 */
public record MarketRequestC2S(boolean openPanel, boolean closePanel) implements CustomPacketPayload {

    public static final Type<MarketRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("stockmarket", "market_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MarketRequestC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, MarketRequestC2S::openPanel,
            ByteBufCodecs.BOOL, MarketRequestC2S::closePanel,
            MarketRequestC2S::new);

    @Override
    public Type<MarketRequestC2S> type() {
        return TYPE;
    }

    public static List<String> readStrings(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readUtf(64));
        }
        return list;
    }

    public static void writeStrings(RegistryFriendlyByteBuf buf, List<String> values) {
        buf.writeVarInt(values.size());
        values.forEach(s -> buf.writeUtf(s, 64));
    }

    public static String readOptionalString(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf(512) : null;
    }

    public static void writeOptionalString(RegistryFriendlyByteBuf buf, String value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            buf.writeUtf(value, 512);
        }
    }
}
