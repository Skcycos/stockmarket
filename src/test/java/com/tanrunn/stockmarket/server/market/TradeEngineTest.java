package com.tanrunn.stockmarket.server.market;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeEngineTest {

    @Test
    void buyDeductsCashAndFees() {
        HoldingAccount account = new HoldingAccount(1000, Map.of());
        TradeEngine.Result result = TradeEngine.buy(account, "aaa", 10, 50, 0.001);
        assertTrue(result.success());
        // 10 * 50 = 500, fee 0.5, cash 499.50
        assertEquals(499.50, result.account().cash(), 0.001);
        assertEquals(50, result.account().holdings().get("aaa"));
    }

    @Test
    void buyRejectedWithoutEnoughCash() {
        HoldingAccount account = new HoldingAccount(100, Map.of());
        TradeEngine.Result result = TradeEngine.buy(account, "aaa", 10, 50, 0.001);
        assertFalse(result.success());
        assertEquals(100, result.account().cash());
    }

    @Test
    void sellCreditsCashAndRemovesHoldings() {
        HoldingAccount account = new HoldingAccount(100, Map.of("aaa", 50));
        TradeEngine.Result result = TradeEngine.sell(account, "aaa", 10, 30, 0.001);
        assertTrue(result.success());
        // 30 * 10 = 300, fee 0.3, cash 399.70
        assertEquals(399.70, result.account().cash(), 0.001);
        assertEquals(20, result.account().holdings().get("aaa"));
    }

    @Test
    void sellAllRemovesStockEntry() {
        HoldingAccount account = new HoldingAccount(0, Map.of("aaa", 10));
        TradeEngine.Result result = TradeEngine.sell(account, "aaa", 5, 10, 0.001);
        assertTrue(result.success());
        assertFalse(result.account().holdings().containsKey("aaa"));
    }

    @Test
    void sellRejectedWithoutHoldings() {
        HoldingAccount account = new HoldingAccount(0, Map.of());
        TradeEngine.Result result = TradeEngine.sell(account, "aaa", 5, 1, 0.001);
        assertFalse(result.success());
    }

    @Test
    void totalValueComputesCashPlusHoldings() {
        HoldingAccount account = new HoldingAccount(500, Map.of("aaa", 10, "bbb", 5));
        double total = account.totalValue(id -> switch (id) {
            case "aaa" -> 10.0;
            case "bbb" -> 20.0;
            default -> 0;
        });
        assertEquals(700.0, total, 0.001);
    }

    @Test
    void portfolioValuationSeparatesMarketAndRealizedPnl() {
        HoldingAccount account = new HoldingAccount(500, Map.of("aaa", 10), Map.of("aaa", 120.0), 7.50);
        assertEquals(100.0, account.holdingsValue(id -> 10.0), 0.001);
        assertEquals(-20.0, account.unrealizedPnl(id -> 10.0), 0.001);
        assertEquals(-12.50, account.totalPnl(id -> 10.0), 0.001);
        assertEquals(600.0, account.totalValue(id -> 10.0), 0.001);
    }

    @Test
    void reserveBuyDeductsCashAndFillAddsShares() {
        HoldingAccount account = new HoldingAccount(1000, Map.of());
        TradeEngine.Result reserved = TradeEngine.reserveBuy(account, 10, 50, 0.001);
        assertTrue(reserved.success());
        assertEquals(499.50, reserved.account().cash(), 0.001);
        assertTrue(reserved.account().holdings().isEmpty(), "shares come only on fill");
        HoldingAccount filled = TradeEngine.fillBuy(reserved.account(), "aaa", 50);
        assertEquals(50, filled.holdings().get("aaa"));
        assertEquals(499.50, filled.cash(), 0.001);
    }

    @Test
    void reserveSellRemovesSharesAndFillCreditsCash() {
        HoldingAccount account = new HoldingAccount(100, Map.of("aaa", 50));
        TradeEngine.Result reserved = TradeEngine.reserveSell(account, "aaa", 30);
        assertTrue(reserved.success());
        assertEquals(20, reserved.account().holdings().get("aaa"));
        HoldingAccount filled = TradeEngine.fillSell(reserved.account(), "aaa", 10, 30, 0.001);
        // 30 * 10 = 300 - 0.3 fee = 399.70
        assertEquals(399.70, filled.cash(), 0.001);
        assertEquals(20, filled.holdings().get("aaa"));
    }

    @Test
    void cancelRefundsReservations() {
        HoldingAccount account = new HoldingAccount(1000, Map.of("aaa", 50));
        HoldingAccount reserved = TradeEngine.reserveBuy(account, 10, 50, 0.001).account();
        HoldingAccount refunded = TradeEngine.refundBuy(reserved, 10, 50, 0.001);
        assertEquals(1000, refunded.cash(), 0.001);

        HoldingAccount reservedSell = TradeEngine.reserveSell(account, "aaa", 30).account();
        HoldingAccount returnedShares = TradeEngine.refundSell(reservedSell, "aaa", 30);
        assertEquals(50, returnedShares.holdings().get("aaa"));
    }

    // 6. 撤销买单能够正确退回预留资金（含手续费，精确到分）
    @Test
    void cancelBuyRefundsReservedCashExactly() {
        HoldingAccount account = new HoldingAccount(1234.56, Map.of());
        HoldingAccount reserved = TradeEngine.reserveBuy(account, 23.45, 20, 0.001).account();
        // 23.45 * 20 = 469.00，手续费 0.47，预留 469.47
        assertEquals(765.09, reserved.cash(), 0.0001);
        HoldingAccount refunded = TradeEngine.refundBuy(reserved, 23.45, 20, 0.001);
        assertEquals(1234.56, refunded.cash(), 0.0001, "refund must restore the exact pre-order cash");
        assertTrue(refunded.holdings().isEmpty());
    }

    // 7. 撤销卖单能够正确退回预留持仓
    @Test
    void cancelSellRefundsReservedShares() {
        HoldingAccount account = new HoldingAccount(100, Map.of("aaa", 50));
        HoldingAccount reserved = TradeEngine.reserveSell(account, "aaa", 30).account();
        assertEquals(20, reserved.holdings().get("aaa"));
        HoldingAccount refunded = TradeEngine.refundSell(reserved, "aaa", 30);
        assertEquals(50, refunded.holdings().get("aaa"));
        assertEquals(100.0, refunded.cash(), 0.0001, "cash is untouched by a sell reservation");
    }

    // 金额对称性：预留与退款必须完全可逆，无浮点漂移
    @Test
    void reserveThenRefundIsExactlyReversible() {
        HoldingAccount account = new HoldingAccount(1000, Map.of("aaa", 10));
        HoldingAccount reserved = TradeEngine.reserveBuy(account, 13.37, 33, 0.001).account();
        HoldingAccount refunded = TradeEngine.refundBuy(reserved, 13.37, 33, 0.001);
        assertEquals(account.cash(), refunded.cash(), 0.0001);
        assertEquals(account.holdings(), refunded.holdings());
    }

    // 限价价格校验：NaN/±Infinity/<0.01 全部拒绝（服务端权威）
    @Test
    void limitPriceValidationRejectsNonFiniteAndTooLow() {
        assertNull(TradeEngine.validatePrice(10.0));
        assertNull(TradeEngine.validatePrice(0.01));
        assertNotNull(TradeEngine.validatePrice(0.009));
        assertNotNull(TradeEngine.validatePrice(-1.0));
        assertNotNull(TradeEngine.validatePrice(Double.NaN));
        assertNotNull(TradeEngine.validatePrice(Double.POSITIVE_INFINITY));
        assertNotNull(TradeEngine.validatePrice(Double.NEGATIVE_INFINITY));
    }

    // 价格先舍入再预留：MarketService.placeOrder 现在保证预留、订单保存、
    // 撤单退款使用同一个舍入后的价格（如 10.009 -> 10.01），杜绝资金误差
    @Test
    void roundedLimitPriceReservesAndRefundsExactly() {
        double raw = 10.009;
        double rounded = PriceModel.round(raw);
        assertEquals(10.01, rounded, 0.0001);
        HoldingAccount account = new HoldingAccount(10000, Map.of());
        HoldingAccount reserved = TradeEngine.reserveBuy(account, rounded, 100, 0.001).account();
        HoldingAccount refunded = TradeEngine.refundBuy(reserved, rounded, 100, 0.001);
        assertEquals(account.cash(), refunded.cash(), 0.0001,
                "reserve and refund must share the same rounded price");
    }

    // 成交结算：卖出成交价按分入账，买入成交只加持仓（资金在挂单时已预留）
    @Test
    void limitOrderSettlementCreditsOrHoldsExactly() {
        HoldingAccount account = new HoldingAccount(1000, Map.of("aaa", 30));
        double reserved = TradeEngine.reserveBuy(account, 10.5, 20, 0.001).account().cash();
        HoldingAccount filledBuy = TradeEngine.fillBuy(TradeEngine.reserveBuy(account, 10.5, 20, 0.001).account(), "aaa", 20);
        assertEquals(reserved, filledBuy.cash(), 0.0001, "buy fill must not touch cash again");

        HoldingAccount filledSell = TradeEngine.fillSell(TradeEngine.reserveSell(account, "aaa", 30).account(), "aaa", 10.5, 30, 0.001);
        // 30 * 10.5 = 315.00 - 0.32 fee = 314.68
        assertEquals(1314.68, filledSell.cash(), 0.0001);
        assertEquals(0, filledSell.holdings().size(), "all reserved shares are gone after the fill");
    }

    @Test
    void buyTracksFeeInclusiveCostBasis() {
        HoldingAccount account = new HoldingAccount(1000, Map.of());
        HoldingAccount bought = TradeEngine.buy(account, "aaa", 10, 50, 0.001).account();
        assertEquals(500.50, bought.costBasis().get("aaa"), 0.0001);
        assertEquals(0.0, bought.realizedPnl(), 0.0001);
    }

    @Test
    void sellTracksRemainingBasisAndRealizedPnl() {
        HoldingAccount account = new HoldingAccount(100, Map.of("aaa", 10), Map.of("aaa", 100.0), 0);
        TradeEngine.Result result = TradeEngine.sell(account, "aaa", 15, 4, 0.001);

        assertTrue(result.success());
        assertEquals(6, result.account().holdings().get("aaa"));
        assertEquals(60.0, result.account().costBasis().get("aaa"), 0.0001);
        // 60.00 gross - 0.06 fee - 40.00 carrying cost
        assertEquals(19.94, result.account().realizedPnl(), 0.0001);
    }

    @Test
    void limitFillAndCancelPreserveCostBasis() {
        HoldingAccount account = new HoldingAccount(1000, Map.of("aaa", 10), Map.of("aaa", 100.0), 0);
        double reservedBasis = TradeEngine.costBasisForSale(account, "aaa", 5);
        HoldingAccount reserved = TradeEngine.reserveSell(account, "aaa", 5).account();
        assertEquals(50.0, reserved.costBasis().get("aaa"), 0.0001);

        HoldingAccount cancelled = TradeEngine.refundSell(reserved, "aaa", 5, reservedBasis);
        assertEquals(100.0, cancelled.costBasis().get("aaa"), 0.0001);

        HoldingAccount filled = TradeEngine.fillSell(reserved, "aaa", 15, 5, 0.001, reservedBasis);
        // 75.00 gross - 0.08 fee - 50.00 carrying cost
        assertEquals(24.92, filled.realizedPnl(), 0.0001);
    }

    @Test
    void splitMultipliesSharesButPreservesTotalCostBasis() {
        HoldingAccount account = new HoldingAccount(50, Map.of("aaa", 10), Map.of("aaa", 123.45), 2.0);
        HoldingAccount split = TradeEngine.applySplit(account, "aaa", 2, 1);
        assertEquals(20, split.holdings().get("aaa"));
        assertEquals(123.45, split.costBasis().get("aaa"), 0.0001);
        assertEquals(account.cash(), split.cash(), 0.0001);
    }

    @Test
    void dividendCreditsCentsWithoutChangingHoldingsOrBasis() {
        HoldingAccount account = new HoldingAccount(10, Map.of("aaa", 7), Map.of("aaa", 70.0), 0);
        HoldingAccount paid = TradeEngine.payDividend(account, 7, 0.05);
        assertEquals(10.35, paid.cash(), 0.0001);
        assertEquals(account.holdings(), paid.holdings());
        assertEquals(account.costBasis(), paid.costBasis());
    }
}
