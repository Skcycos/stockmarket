package com.tanrunn.stockmarket.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossModApiContractTest {
    @Test
    void publicQuoteUsesCentsAndKeepsChangePercentage() {
        StockQuote quote = new StockQuote("aaa", "测试", 1250, 1000, 1300, 900, 42,
                List.of(new CandleSnapshot(1, 1000, 1250, 1300, 900, 42)));
        assertEquals(1_250, quote.priceCents());
        assertEquals(25.0, quote.changePct(), 0.0001);
        assertEquals(1, quote.history().size());
        assertEquals(1_250, quote.history().get(0).closeCents());
    }

    @Test
    void cashRulesRejectInvalidAmountsAndProtectBalance() {
        assertEquals("金额必须大于 0", CashTransactionRules.validatePositiveAmount(0));
        assertEquals("金额必须大于 0", CashTransactionRules.validatePositiveAmount(-1));
        assertTrue(CashTransactionRules.canWithdraw(100, 100));
        assertTrue(!CashTransactionRules.canWithdraw(99, 100));
        assertEquals(150, CashTransactionRules.applyDelta(100, 50));
        assertThrows(ArithmeticException.class, () -> CashTransactionRules.applyDelta(Long.MAX_VALUE, 1));
    }

    @Test
    void publicRecordsCopyCollectionsAndKeepRequestIdentity() {
        TransactionRecord transaction = new TransactionRecord("tx-1", "quest-42", 7,
                1000, 12345, "quests", "完成新手任务");
        assertEquals("quest-42", transaction.requestId());
        assertEquals(12345, transaction.balanceCents());

        AccountSnapshot snapshot = new AccountSnapshot(
                UUID.randomUUID(), 100, 200, 100, 0, 0, 0, 0, 0, 100, 0,
                1, 0, Map.of("aaa", 1), Map.of("aaa", 100L),
                List.of(new OrderSnapshot(7, "aaa", true, 1250, 2)),
                List.of(new TradeSnapshot(8, "aaa", true, 1200, 1, 2)), List.of());
        assertEquals(100, snapshot.cashCents());
        assertTrue(snapshot.holdings().containsKey("aaa"));
        assertEquals(1250, snapshot.orders().get(0).priceCents());
        assertEquals(2, snapshot.trades().get(0).feeCents());
    }
}
