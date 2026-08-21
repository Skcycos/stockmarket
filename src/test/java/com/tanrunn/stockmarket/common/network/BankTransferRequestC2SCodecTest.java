package com.tanrunn.stockmarket.common.network;

import com.tanrunn.stockmarket.api.BankTransferRequest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BankTransferRequestC2S} 字节级 codec 测试：方向相关金额字段往返；
 * 非法方向、负的请求金额、requestId 超长一律解码阶段直接拒绝。
 */
class BankTransferRequestC2SCodecTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    void roundTripsDepositCopperRequest() {
        BankTransferRequestC2S payload = new BankTransferRequestC2S(
                BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES, 10, 0, "req-123");
        RegistryFriendlyByteBuf buf = buffer();
        BankTransferRequestC2S.STREAM_CODEC.encode(buf, payload);
        BankTransferRequestC2S decoded = BankTransferRequestC2S.STREAM_CODEC.decode(buf);
        assertEquals(payload, decoded);
        assertEquals(10, decoded.toRequest().requestedCopper());
        assertEquals(0, decoded.toRequest().requestedSecuritiesCents());
    }

    @Test
    void roundTripsWithdrawCentsRequest() {
        BankTransferRequestC2S payload = new BankTransferRequestC2S(
                BankTransferRequest.Direction.WITHDRAW_TO_BANK, 0, 250, "req-456");
        RegistryFriendlyByteBuf buf = buffer();
        BankTransferRequestC2S.STREAM_CODEC.encode(buf, payload);
        assertEquals(payload, BankTransferRequestC2S.STREAM_CODEC.decode(buf));
    }

    @Test
    void roundTripsMaxLengthRequestId() {
        String maxId = "a".repeat(BankTransferRequest.MAX_REQUEST_ID_LENGTH);
        BankTransferRequestC2S payload = new BankTransferRequestC2S(
                BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES, 1, 0, maxId);
        RegistryFriendlyByteBuf buf = buffer();
        BankTransferRequestC2S.STREAM_CODEC.encode(buf, payload);
        assertEquals(payload, BankTransferRequestC2S.STREAM_CODEC.decode(buf));
    }

    @Test
    void rejectsOutOfRangeDirection() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(99);
        buf.writeVarLong(1);
        buf.writeVarLong(0);
        buf.writeUtf("r", 64);
        assertThrows(RuntimeException.class, () -> BankTransferRequestC2S.STREAM_CODEC.decode(buf));
    }

    @Test
    void rejectsNegativeCopper() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(0);
        buf.writeVarLong(-1);
        buf.writeVarLong(0);
        buf.writeUtf("r", 64);
        assertThrows(RuntimeException.class, () -> BankTransferRequestC2S.STREAM_CODEC.decode(buf));
    }

    @Test
    void rejectsNegativeSecuritiesCents() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);
        buf.writeVarLong(0);
        buf.writeVarLong(-5);
        buf.writeUtf("r", 64);
        assertThrows(RuntimeException.class, () -> BankTransferRequestC2S.STREAM_CODEC.decode(buf));
    }

    @Test
    void rejectsOverlongRequestId() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(0);
        buf.writeVarLong(1);
        buf.writeVarLong(0);
        buf.writeUtf("a".repeat(BankTransferRequest.MAX_REQUEST_ID_LENGTH + 1), 128);
        assertThrows(RuntimeException.class, () -> BankTransferRequestC2S.STREAM_CODEC.decode(buf));
    }
}
