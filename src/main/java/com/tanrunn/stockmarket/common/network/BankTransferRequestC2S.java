package com.tanrunn.stockmarket.common.network;

import com.tanrunn.stockmarket.api.BankTransferRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: 玩家主动的银行 ⇄ 证券账户入金/出金请求。
 *
 * <p>严格 codec：方向枚举越界、负的请求金额、requestId 超长都会在解码阶段直接拒绝
 * （抛异常 → 包被判定非法断开）；服务端 handler/service 再做完整二次校验（方向相关的
 * &gt;0 判断、金额上限、冷却、幂等）。客户端携带的是<b>原始请求</b>（入金铜币数 /
 * 出金证券金额分 + 原始 requestId），不携带任何余额或最终结果。</p>
 *
 * @param direction                DEPOSIT_TO_SECURITIES / WITHDRAW_TO_BANK
 * @param requestedCopper          入金的铜币数量（DEPOSIT 时 &gt;0）
 * @param requestedSecuritiesCents 出金请求的证券金额（分）（WITHDRAW 时 &gt;0）
 * @param requestId                原始客户端幂等键（≤ {@value BankTransferRequest#MAX_REQUEST_ID_LENGTH}）
 */
public record BankTransferRequestC2S(
        BankTransferRequest.Direction direction,
        long requestedCopper,
        long requestedSecuritiesCents,
        String requestId) implements CustomPacketPayload {

    public static final Type<BankTransferRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("stockmarket", "bank_transfer_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BankTransferRequestC2S> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BankTransferRequestC2S decode(RegistryFriendlyByteBuf buf) {
            int directionIndex = buf.readVarInt();
            if (directionIndex < 0 || directionIndex >= BankTransferRequest.Direction.values().length) {
                throw new IllegalArgumentException("非法转账方向: " + directionIndex);
            }
            long copper = buf.readVarLong();
            if (copper < 0) {
                throw new IllegalArgumentException("入金铜币数量不能为负");
            }
            long cents = buf.readVarLong();
            if (cents < 0) {
                throw new IllegalArgumentException("出金证券金额不能为负");
            }
            String requestId = buf.readUtf(BankTransferRequest.MAX_REQUEST_ID_LENGTH);
            return new BankTransferRequestC2S(BankTransferRequest.Direction.values()[directionIndex],
                    copper, cents, requestId);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, BankTransferRequestC2S value) {
            buf.writeVarInt(value.direction().ordinal());
            buf.writeVarLong(value.requestedCopper());
            buf.writeVarLong(value.requestedSecuritiesCents());
            buf.writeUtf(value.requestId() == null ? "" : value.requestId(),
                    BankTransferRequest.MAX_REQUEST_ID_LENGTH);
        }
    };

    /** 转成纯业务值对象（供服务端权威逻辑复用）。 */
    public BankTransferRequest toRequest() {
        return new BankTransferRequest(direction, requestedCopper, requestedSecuritiesCents, requestId);
    }

    @Override
    public Type<BankTransferRequestC2S> type() {
        return TYPE;
    }
}
