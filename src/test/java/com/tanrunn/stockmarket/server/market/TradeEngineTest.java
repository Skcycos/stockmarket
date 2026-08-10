package com.tanrunn.stockmarket.server.market;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
