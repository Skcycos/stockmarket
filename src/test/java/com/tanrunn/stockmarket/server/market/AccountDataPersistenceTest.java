package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.api.TransactionRecord;
import com.tanrunn.stockmarket.api.BankTransferService;
import com.tanrunn.stockmarket.common.TradeInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountDataPersistenceTest {

    @Test
    void dailyExternalCashFlowOnlyIncludesLcBankBridge() {
        AccountData data = new AccountData();
        data.ledger.add(new TransactionRecord("lc-deposit", "request-1", 7,
                10_000, 11_000, BankTransferService.SOURCE, "入金到证券账户"));
        data.ledger.add(new TransactionRecord("lc-withdraw", "request-2", 7,
                -3_000, 8_000, BankTransferService.SOURCE, "提现到银行"));
        data.ledger.add(new TransactionRecord("quest", "request-3", 7,
                5_000, 13_000, "quest", "任务奖励"));
        data.ledger.add(new TransactionRecord("other-day", "request-4", 6,
                99_000, 99_000, BankTransferService.SOURCE, "旧日期"));

        assertEquals(7_000, data.dailyExternalCashFlowCents(7));
        assertEquals(99_000, data.dailyExternalCashFlowCents(6));
    }

    /**
     * AttachmentType.copyOnDeath copies a serializable attachment by serializing
     * and deserializing it. This protects the full account state during respawn.
     */
    @Test
    void accountRoundTripRetainsEstablishedPlayerState() {
        AccountData original = new AccountData();
        original.initialized = true;
        original.cash = 58_432.75;
        original.holdings.put("tea", 42);
        original.costBasis.put("tea", 4_000.50);
        original.realizedPnl = 1_234.25;
        original.dailyBaselineDay = 99;
        original.dailyBaselineValue = 55_000.25;
        original.lastCorporateActionId = 17;
        original.trades.add(new TradeInfo(99, "tea", true, 100.25, 42, 4.21));
        original.ledger.add(new TransactionRecord("deposit-1", "request-1", 99,
                500_000, 5_843_275, "test", "regression"));

        AccountData restored = new AccountData();
        restored.deserializeNBT(null, original.serializeNBT(null));

        assertTrue(restored.initialized, "a respawned player must not be initialized as new");
        assertEquals(58_432.75, restored.cash);
        assertEquals(42, restored.holdings.get("tea"));
        assertEquals(4_000.50, restored.costBasis.get("tea"));
        assertEquals(1_234.25, restored.realizedPnl);
        assertEquals(99, restored.dailyBaselineDay);
        assertEquals(55_000.25, restored.dailyBaselineValue);
        assertEquals(17, restored.lastCorporateActionId);
        assertEquals(original.trades, restored.trades);
        assertEquals(original.ledger, restored.ledger);
    }
}
