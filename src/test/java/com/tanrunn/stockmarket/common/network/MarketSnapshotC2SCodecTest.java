package com.tanrunn.stockmarket.common.network;

import com.tanrunn.stockmarket.common.AccountInfo;
import com.tanrunn.stockmarket.common.OrderInfo;
import com.tanrunn.stockmarket.common.StockInfo;
import com.tanrunn.stockmarket.common.TradeInfo;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarketSnapshotC2S} 字节级 codec 测试：银行桥接字段（可用/余额/名称）往返，
 * 未安装时默认不可用。
 */
class MarketSnapshotC2SCodecTest {

    private static final AccountInfo ACCOUNT = new AccountInfo(
            100.0, 200.0, 100.0, 0, 0, 0, 0, 50.0, 100.0, 0,
            2, 0, Map.of("aaa", 2), Map.of("aaa", 100.0),
            List.of(new OrderInfo(7, "aaa", true, 1.0, 2)),
            List.of(new TradeInfo(8, "aaa", true, 1.0, 1, 0.01)));

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    void roundTripsBankBridgeFields() {
        MarketSnapshotC2S payload = new MarketSnapshotC2S(true, "msg", List.of(), ACCOUNT,
                List.of(), List.of(), 9999, true, 12345, "LC 银行账户");
        RegistryFriendlyByteBuf buf = buffer();
        MarketSnapshotC2S.STREAM_CODEC.encode(buf, payload);
        MarketSnapshotC2S decoded = MarketSnapshotC2S.STREAM_CODEC.decode(buf);
        assertTrue(decoded.bankBridgeAvailable());
        assertEquals(12345, decoded.bankBalanceCopper());
        assertEquals("LC 银行账户", decoded.bankBridgeName());
        assertEquals(100.0, decoded.account().cash(), 0.0001);
        assertEquals(2, decoded.account().holdings().get("aaa"));
    }

    @Test
    void defaultSnapshotIsBankUnavailable() {
        MarketSnapshotC2S payload = new MarketSnapshotC2S(false, "x", List.of(), ACCOUNT);
        assertEquals(0, payload.bankBalanceCopper());
        assertFalse(payload.bankBridgeAvailable());
        RegistryFriendlyByteBuf buf = buffer();
        MarketSnapshotC2S.STREAM_CODEC.encode(buf, payload);
        MarketSnapshotC2S decoded = MarketSnapshotC2S.STREAM_CODEC.decode(buf);
        assertFalse(decoded.bankBridgeAvailable());
    }

    @Test
    void roundTripsWithStockRows() {
        StockInfo stock = new StockInfo("aaa", "测试", 1.25, 1.0, 1.3, 0.9, 42,
                List.of(), "industry", false, 0);
        MarketSnapshotC2S payload = new MarketSnapshotC2S(false, "", List.of(stock), ACCOUNT,
                List.of(), List.of(), 1234, true, 99, "");
        RegistryFriendlyByteBuf buf = buffer();
        MarketSnapshotC2S.STREAM_CODEC.encode(buf, payload);
        MarketSnapshotC2S decoded = MarketSnapshotC2S.STREAM_CODEC.decode(buf);
        assertEquals(1, decoded.stocks().size());
        assertEquals("aaa", decoded.stocks().get(0).id());
        assertEquals(1234, decoded.maxOrderQty());
    }
}
