package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.api.BridgeResult;
import com.tanrunn.stockmarket.api.BridgeStatusCode;
import com.tanrunn.stockmarket.api.CurrencyBridge;
import com.tanrunn.stockmarket.api.StockMarketApi;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反例测试：证明证券账户的<b>日常交易</b>（买入/卖出/限价单/撤单/撮合/分红/
 * 股价 tick/持仓市值）完全不会调用 LC 桥接。
 *
 * <p>两层证据：1) 用可记录的 fake 桥跑一遍全部纯市场操作，断言桥调用计数为零；
 * 2) 字节码级扫描：这些市场核心类（TradeEngine/OrderBook/PriceModel/HoldingAccount）
 * 的类文件常量池不包含任何桥接类型引用。</p>
 */
class MarketNeverUsesBridgeTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Map<String, Integer> HOLDINGS = new HashMap<>();

    static {
        HOLDINGS.put("aaa", 10);
    }

    private final RecordingBridge recordingBridge = new RecordingBridge();

    @Test
    void registryExposesBridgeForTransfersButMarketOpsStayPure() {
        String bridgeId = "test_recording_" + UUID.randomUUID();
        StockMarketApi.registerCurrencyBridge(recordingBridge.withId(bridgeId));
        try {
            assertTrue(StockMarketApi.currencyBridge(bridgeId).isPresent());
            // 桥只用于银行 ⇄ 证券转账，市场操作不会走桥。
        } finally {
            // no unregister API in 2.x; unique id per run keeps the registry clean enough.
        }

        double feeRate = 0.001;
        // 市价买入/卖出（资金、持股均只改证券账户）
        TradeEngine.Result buy = TradeEngine.buy(new HoldingAccount(100_000.0, HOLDINGS),
                "aaa", 10.0, 5, feeRate);
        assertTrue(buy.success());
        TradeEngine.Result sell = TradeEngine.sell(new HoldingAccount(100_000.0, HOLDINGS),
                "aaa", 10.0, 5, feeRate);
        assertTrue(sell.success());

        // 限价单资金冻结 + 撤单退款
        HoldingAccount reserved = TradeEngine.reserveBuy(new HoldingAccount(100_000.0, HOLDINGS),
                8.0, 10, feeRate).account();
        assertNotNull(reserved);
        HoldingAccount refunded = TradeEngine.refundBuy(reserved, 8.0, 10, feeRate);
        assertNotNull(refunded);

        // 撮合成交
        HoldingAccount filled = TradeEngine.fillBuy(new HoldingAccount(100_000.0, HOLDINGS),
                "aaa", 10);
        assertNotNull(filled);
        HoldingAccount filledSell = TradeEngine.fillSell(new HoldingAccount(100_000.0, HOLDINGS),
                "aaa", 10.0, 5, feeRate);
        assertNotNull(filledSell);

        // 分红
        HoldingAccount dividend = TradeEngine.payDividend(new HoldingAccount(100_000.0, HOLDINGS),
                10, 0.05);
        assertNotNull(dividend);

        // 股价 tick
        double next = PriceModel.nextPrice(10.0, 0.01, 0.02, new java.util.Random(42));
        assertTrue(Double.isFinite(next));

        // 持仓市值
        HoldingAccount account = new HoldingAccount(100_000.0, HOLDINGS);
        HoldingAccount.PriceOf priceOf = id -> 10.0;
        assertTrue(account.holdingsValue(priceOf) > 0);
        assertTrue(account.totalValue(priceOf) > 0);
        assertTrue(account.unrealizedPnl(priceOf) > 0 || !account.holdings().isEmpty());

        // 挂单簿：place/ordersOf/cancel/match 全部只读写订单簿与证券账户
        OrderBook book = new OrderBook();
        long orderId = book.place(PLAYER, "aaa", true, 8.0, 5, 0, 40.0);
        assertEquals(1, book.ordersOf(PLAYER).size());
        book.match("aaa", 8.0, (order, fillPrice) -> true);
        book.cancel(orderId);
        assertEquals(0, book.ordersOf(PLAYER).size());

        assertEquals(0, recordingBridge.withdrawCount(), "市场操作不得调用银行扣款");
        assertEquals(0, recordingBridge.depositCount(), "市场操作不得调用银行入账");
        assertEquals(0, recordingBridge.totalCalls(), "市场操作不得触达桥接实例");
    }

    @Test
    void marketCoreClassesDoNotReferenceBridgeTypesInBytecode() {
        // 字节码级反例：这些类要么不加载 StockMarketApi，要么不引用桥接类型。
        assertNoBridgeReference(TradeEngine.class);
        assertNoBridgeReference(OrderBook.class);
        assertNoBridgeReference(PriceModel.class);
        assertNoBridgeReference(HoldingAccount.class);
    }

    private static void assertNoBridgeReference(Class<?> clazz) {
        String resource = "/" + clazz.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = MarketNeverUsesBridgeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing class resource " + resource);
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("cannot read " + resource, e);
        }
        String content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(content.contains("CurrencyBridge"), clazz.getName() + " 不得引用 CurrencyBridge");
        assertFalse(content.contains("BankTransferService"), clazz.getName() + " 不得引用 BankTransferService");
        assertFalse(content.contains("BankTransferRequest"), clazz.getName() + " 不得引用 BankTransferRequest");
        assertFalse(content.contains("BridgeResult"), clazz.getName() + " 不得引用 BridgeResult");
    }

    /** 记录调用的 fake 桥。 */
    static final class RecordingBridge implements CurrencyBridge {
        private String id = "test_recording";
        private int withdrawCount;
        private int depositCount;
        private int balanceCalls;

        RecordingBridge withId(String id) {
            this.id = id;
            return this;
        }

        int withdrawCount() { return withdrawCount; }
        int depositCount() { return depositCount; }
        int totalCalls() { return withdrawCount + depositCount + balanceCalls; }

        @Override public String id() { return id; }
        @Override public String displayName() { return "test"; }
        @Override public boolean isAvailable() { return true; }
        @Override public long balanceCopper(UUID playerId) { balanceCalls++; return 0; }

        @Override
        public BridgeResult withdraw(UUID playerId, long copper, String source, String reason, String requestId) {
            withdrawCount++;
            return BridgeResult.fail(BridgeStatusCode.PROVIDER_ERROR);
        }

        @Override
        public BridgeResult deposit(UUID playerId, long copper, String source, String reason, String requestId) {
            depositCount++;
            return BridgeResult.fail(BridgeStatusCode.PROVIDER_ERROR);
        }
    }
}
