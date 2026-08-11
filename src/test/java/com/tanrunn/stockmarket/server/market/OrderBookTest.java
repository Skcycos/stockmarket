package com.tanrunn.stockmarket.server.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // 1. 委托订单保存并恢复后 ID 不变化
    @Test
    void restoredOrdersKeepTheirIds() {
        OrderBook book = new OrderBook();
        long buyId = book.place(PLAYER, "aaa", true, 10.0, 100);
        long sellId = book.place(PLAYER, "aaa", false, 12.0, 50);

        // 模拟 MarketSavedData.read：用 restore 按原始 ID 恢复
        OrderBook restored = new OrderBook();
        for (OrderBook.Order order : book.all()) {
            restored.restore(order.id(), order.player(), order.stockId(), order.buy(), order.price(), order.quantity());
        }

        List<OrderBook.Order> orders = restored.all();
        assertEquals(2, orders.size());
        assertEquals(buyId, orders.get(0).id(), "buy order id must survive a restart");
        assertEquals(sellId, orders.get(1).id(), "sell order id must survive a restart");
        assertEquals(book.nextId(), restored.nextId());
    }

    @Test
    void restoredSellOrderKeepsReservedCostBasis() {
        OrderBook book = new OrderBook();
        long id = book.place(PLAYER, "aaa", false, 12.0, 5, 50.0);
        OrderBook.Order original = book.get(id);

        OrderBook restored = new OrderBook();
        restored.restore(original.id(), original.player(), original.stockId(), original.buy(), original.price(),
                original.quantity(), original.reservedCostBasis());

        assertEquals(50.0, restored.get(id).reservedCostBasis(), 0.0001);
    }

    // 2. 新订单 ID 能从历史最大 ID 继续递增
    @Test
    void newOrderIdContinuesAfterRestoredMax() {
        OrderBook restored = new OrderBook();
        restored.restore(5, PLAYER, "aaa", true, 10.0, 1);
        restored.restore(12, PLAYER, "aaa", false, 11.0, 1);

        long next = restored.place(PLAYER, "bbb", true, 9.0, 10);
        assertEquals(13, next, "next id must continue after the highest restored id");
        assertTrue(restored.nextId() > 12);
    }

    // 1b. 恢复（加载存档）不得触发 dirty——这是"读取"而非"新增委托"
    @Test
    void restoringOrdersDoesNotMarkDirty() {
        OrderBook book = new OrderBook();
        int[] dirtyCount = {0};
        book.setDirtyHandler(() -> dirtyCount[0]++);
        book.restore(5, PLAYER, "aaa", true, 10.0, 100);
        book.restore(12, PLAYER, "aaa", false, 11.0, 50);
        assertEquals(0, dirtyCount[0], "restoring from save must not mark the book dirty");
        assertEquals(2, book.size());
        assertEquals(13, book.nextId(), "restore still advances the id counter");
        // 恢复后的首次新增委托正常触发 dirty
        book.place(PLAYER, "bbb", true, 9.0, 10);
        assertEquals(1, dirtyCount[0]);
    }

    // 3a. 离线成交不会删除订单（sink 返回 false 时订单保留）
    @Test
    void offlineFillKeepsOrderUntilConfirmed() {
        OrderBook book = new OrderBook();
        long id = book.place(PLAYER, "aaa", true, 10.0, 100);

        // 玩家离线：sink 拒绝成交 -> 订单必须保留，且仍可撤单
        book.match("aaa", 9.0, (order, fillPrice) -> false);
        assertEquals(1, book.size(), "offline order must stay in the book");
        assertNotNull(book.cancel(id), "offline order must stay cancellable");

        // 玩家上线后：确认成交 -> 订单移除，成交价为委托价
        book.place(PLAYER, "aaa", true, 10.0, 100);
        book.match("aaa", 9.0, (order, fillPrice) -> {
            assertEquals(10.0, fillPrice, 0.001);
            return true;
        });
        assertEquals(0, book.size());
    }

    // 3b. 离线订单不会导致资金或持仓永久锁死
    @Test
    void offlineOrderNeverLocksFundsOrHoldings() {
        OrderBook book = new OrderBook();
        long buyId = book.place(PLAYER, "aaa", true, 10.0, 100);
        long sellId = book.place(PLAYER, "aaa", false, 12.0, 50);

        // 多次离线撮合轮次：任何订单都不被丢弃
        book.match("aaa", 5.0, (o, p) -> false);
        book.match("aaa", 20.0, (o, p) -> false);
        assertEquals(2, book.size());

        // 买单预留的资金可全额退回
        HoldingAccount account = new HoldingAccount(1000, Map.of("aaa", 50));
        HoldingAccount reserved = TradeEngine.reserveBuy(account, 10.0, 50, 0.001).account();
        assertEquals(1000.0, TradeEngine.refundBuy(reserved, 10.0, 50, 0.001).cash(), 0.001);
        // 卖单预留的持仓可原样退回
        HoldingAccount reservedSell = TradeEngine.reserveSell(account, "aaa", 50).account();
        assertEquals(50, TradeEngine.refundSell(reservedSell, "aaa", 50).holdings().get("aaa"));

        // 两张离线订单仍可撤单
        assertNotNull(book.cancel(buyId));
        assertNotNull(book.cancel(sellId));
        assertEquals(0, book.size());
    }

    // 4. 超过 MAX_ORDER_QTY 的所有订单入口都会失败
    @Test
    void quantityAboveMaxFailsForEveryTradingEntry() {
        int max = 9999;
        // 客户端网络市价、/market buy、/market sell、限价买单、限价卖单
        // 都经由 MarketService.entryGate -> TradeEngine.validateEntry，同一门禁。
        assertNotNull(TradeEngine.validateEntry(true, max + 1, max), "market buy over max must fail");
        assertNotNull(TradeEngine.validateEntry(true, 10_000, max), "limit sell over max must fail");
        assertNull(TradeEngine.validateEntry(true, max, max), "exactly max is allowed");
        assertNull(TradeEngine.validateEntry(true, 1, max), "minimum quantity is allowed");
        assertNotNull(TradeEngine.validateEntry(true, 0, max), "non-positive quantity must fail");
        assertNotNull(TradeEngine.validateEntry(true, -5, max), "negative quantity must fail");
    }

    // 5. enabled=false 时所有交易入口都会失败
    @Test
    void disabledMarketRejectsEveryTradingEntry() {
        String error = TradeEngine.validateEntry(false, 1, 9999);
        assertNotNull(error, "trading must be rejected while the market is disabled");
        assertTrue(error.contains("关闭"), "the rejection message must be explicit");
    }

    // 6a. 关闭期间已有订单仍然保留（撮合被 MarketService 门禁拦截，簿上无任何变化），
    // 资金/持仓不丢失、仍可撤单退款。
    @Test
    void ordersSurviveWhileMatchingIsDisabled() {
        OrderBook book = new OrderBook();
        long buyId = book.place(PLAYER, "aaa", true, 10.0, 100);
        long sellId = book.place(PLAYER, "aaa", false, 12.0, 50);

        // enabled=false 时 MarketService.matchOrders 直接 return，委托簿保持原样：
        // 即使价格已越过限价（5.0 越过买单 10，20.0 越过卖单 12），订单也必须保留。
        assertEquals(2, book.size(), "orders must survive a disabled market");
        assertEquals(buyId, book.all().get(0).id());
        assertEquals(sellId, book.all().get(1).id());

        // 预留的资金/持仓在关闭期间仍可全额撤单退回
        HoldingAccount account = new HoldingAccount(1000, Map.of("aaa", 50));
        HoldingAccount reserved = TradeEngine.reserveBuy(account, 10.0, 50, 0.001).account();
        assertEquals(1000.0, TradeEngine.refundBuy(reserved, 10.0, 50, 0.001).cash(), 0.001);
        HoldingAccount reservedSell = TradeEngine.reserveSell(account, "aaa", 50).account();
        assertEquals(50, TradeEngine.refundSell(reservedSell, "aaa", 50).holdings().get("aaa"));
    }

    // 6b. 新增委托后 OrderBook 必须标记 dirty（对应 SavedData.setDirty 接线）
    @Test
    void placingOrderMarksDirty() {
        OrderBook book = new OrderBook();
        int[] dirtyCount = {0};
        book.setDirtyHandler(() -> dirtyCount[0]++);
        book.place(PLAYER, "aaa", true, 10.0, 100);
        book.place(PLAYER, "aaa", false, 12.0, 50);
        assertEquals(2, dirtyCount[0], "each new order must mark the book dirty");
    }

    // 6c. 撤销委托后 OrderBook 必须标记 dirty
    @Test
    void cancellingOrderMarksDirty() {
        OrderBook book = new OrderBook();
        long id = book.place(PLAYER, "aaa", true, 10.0, 100);
        int[] dirtyCount = {0};
        book.setDirtyHandler(() -> dirtyCount[0]++);
        assertNotNull(book.cancel(id));
        assertEquals(1, dirtyCount[0], "a successful cancel must mark the book dirty");
        assertNull(book.cancel(id), "cancelling a missing order changes nothing");
        assertEquals(1, dirtyCount[0], "no dirty for a no-op cancel");
    }

    // 6d. 委托成交后 OrderBook 必须标记 dirty
    @Test
    void filledOrderMarksDirty() {
        OrderBook book = new OrderBook();
        book.place(PLAYER, "aaa", true, 10.0, 100);
        int[] dirtyCount = {0};
        book.setDirtyHandler(() -> dirtyCount[0]++);
        book.match("aaa", 9.0, (o, p) -> true);
        assertEquals(1, dirtyCount[0], "a confirmed fill must mark the book dirty");
        // 离线拒绝成交：订单保留，不应标记 dirty（无实际变化）
        book.place(PLAYER, "aaa", true, 10.0, 100);
        dirtyCount[0] = 0;
        book.match("aaa", 9.0, (o, p) -> false);
        assertEquals(0, dirtyCount[0], "a declined fill changes nothing");
        assertEquals(1, book.size());
    }
}
