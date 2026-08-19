package com.tanrunn.stockmarket.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link StockMarketApi#summary} 与 {@link MarketSummary} 的测试。
 *
 * <p>真实服务端路径（MarketService 已启动）无法在单测中构造；这里覆盖
 * {@code fromAccount} 的纯字段转换（含 holdingKinds 过滤）、null 玩家守卫与
 * 方法签名反射守卫。非服务端线程守卫由 {@link StockMarketApi#account} 的
 * 既有语义保证（summary 只做委托）。</p>
 */
class MarketSummaryTest {

    private static AccountSnapshot account(long cash, long total, long daily,
                                           Map<String, Integer> holdings, List<OrderSnapshot> orders) {
        return new AccountSnapshot(UUID.randomUUID(), cash, total, cash, 0, 0, daily, 0,
                0, 0, 0, 0, 0, holdings, Map.of(), orders, List.of(), List.of());
    }

    @Test
    void fromAccountMapsEveryFieldStrictly() {
        AccountSnapshot snapshot = account(1_000, 5_000, 123,
                Map.of("aaa", 3, "bbb", 7),
                List.of(new OrderSnapshot(1, "aaa", true, 1_250, 2),
                        new OrderSnapshot(2, "bbb", false, 900, 5)));
        MarketSummary summary = StockMarketApi.fromAccount(snapshot);
        assertEquals(1_000, summary.cashCents());
        assertEquals(5_000, summary.totalValueCents());
        assertEquals(123, summary.dailyPnlCents());
        assertEquals(2, summary.holdingKinds());
        assertEquals(2, summary.openOrderCount());
    }

    @Test
    void holdingKindsIgnoresZeroAndNegativeEntries() {
        AccountSnapshot snapshot = account(0, 0, 0,
                Map.of("aaa", 5, "bbb", 0, "ccc", -3, "ddd", 1),
                List.of());
        assertEquals(2, StockMarketApi.fromAccount(snapshot).holdingKinds());
    }

    @Test
    void fromAccountHandlesEmptyHoldingsAndOrders() {
        AccountSnapshot snapshot = account(0, 0, 0, Map.of(), List.of());
        MarketSummary summary = StockMarketApi.fromAccount(snapshot);
        assertEquals(0, summary.holdingKinds());
        assertEquals(0, summary.openOrderCount());
        assertEquals(0, summary.cashCents());
        assertEquals(0, summary.totalValueCents());
        assertEquals(0, summary.dailyPnlCents());
    }

    @Test
    void summaryRejectsNullPlayer() {
        assertThrows(IllegalArgumentException.class, () -> StockMarketApi.summary(null));
    }

    @Test
    void summaryRejectsPlayerWithoutServerThread() throws Exception {
        // mock 玩家配一个"非服务端主线程"的 mock 服务端，验证 account() 的线程守卫抛出 ISE。
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(false);
        Field serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        assertThrows(IllegalStateException.class, () -> StockMarketApi.summary(player));
    }

    @Test
    void summarySignatureIsPublicStaticReturningPublicRecord() throws Exception {
        Method method = StockMarketApi.class.getMethod("summary", ServerPlayer.class);
        assertTrue(Modifier.isPublic(method.getModifiers()), "summary 必须 public");
        assertTrue(Modifier.isStatic(method.getModifiers()), "summary 必须 static");
        Class<?> returnType = method.getReturnType();
        assertTrue(returnType.isRecord(), "summary 必须返回 record");
        assertTrue(Modifier.isPublic(returnType.getModifiers()), "返回 record 必须 public");
        assertEquals("MarketSummary", returnType.getSimpleName());
    }
}
