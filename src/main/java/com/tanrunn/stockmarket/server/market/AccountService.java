package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.Config;
import com.tanrunn.stockmarket.api.TransactionRecord;
import com.tanrunn.stockmarket.common.TradeInfo;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * Player attachment entry point. Account data is stored on the player entity
 * and saved with the player dat.
 */
public final class AccountService {
    private AccountService() {
    }

    public static HoldingAccount get(net.minecraft.server.level.ServerPlayer player) {
        return data(player).toView();
    }

    /** Ensures a cross-Mod caller can use an account immediately after login. */
    public static HoldingAccount ensureInitialized(net.minecraft.server.level.ServerPlayer player) {
        AccountData data = data(player);
        if (!data.initialized) {
            data.initialized = true;
            data.cash = Config.INITIAL_CASH.get();
            player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
        }
        return data.toView();
    }

    public static void set(net.minecraft.server.level.ServerPlayer player, HoldingAccount account) {
        AccountData data = data(player);
        data.apply(account);
        player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
    }

    public static void reset(net.minecraft.server.level.ServerPlayer player) {
        AccountData data = data(player);
        data.initialized = true;
        data.cash = com.tanrunn.stockmarket.Config.INITIAL_CASH.get();
        data.holdings.clear();
        data.costBasis.clear();
        data.realizedPnl = 0;
        data.dailyBaselineDay = Long.MIN_VALUE;
        data.dailyBaselineValue = 0;
        MarketService service = MarketService.get();
        data.lastCorporateActionId = service == null ? 0 : service.latestCorporateActionId();
        data.trades.clear();
        data.ledger.clear();
        data.transfers.clear();
        player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
    }

    public static java.util.List<TradeInfo> trades(net.minecraft.server.level.ServerPlayer player) {
        return java.util.List.copyOf(data(player).trades);
    }

    public static void recordTrade(net.minecraft.server.level.ServerPlayer player, TradeInfo trade) {
        AccountData data = data(player);
        data.addTrade(trade);
        player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
    }

    public static java.util.List<TransactionRecord> ledger(net.minecraft.server.level.ServerPlayer player) {
        return java.util.List.copyOf(data(player).ledger);
    }

    public static TransactionRecord findTransactionByRequestId(
            net.minecraft.server.level.ServerPlayer player, String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        for (TransactionRecord transaction : data(player).ledger) {
            if (requestId.equals(transaction.requestId())) return transaction;
        }
        return null;
    }

    public static void recordTransaction(net.minecraft.server.level.ServerPlayer player,
                                         TransactionRecord transaction) {
        AccountData data = data(player);
        data.addTransaction(transaction);
        player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
    }

    // ---- bank transfer ledger ----

    public static java.util.List<com.tanrunn.stockmarket.api.BankTransferRecord> transfers(
            net.minecraft.server.level.ServerPlayer player) {
        return java.util.List.copyOf(data(player).transfers);
    }

    public static com.tanrunn.stockmarket.api.BankTransferRecord findTransferByRequestId(
            net.minecraft.server.level.ServerPlayer player, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        // 防重：详细账本优先，其次持久化墓碑（旧 requestId 依旧被挡住）。
        return data(player).findByRequestId(requestId);
    }

    public static void recordTransfer(net.minecraft.server.level.ServerPlayer player,
                                      com.tanrunn.stockmarket.api.BankTransferRecord transfer) {
        AccountData data = data(player);
        data.addTransfer(transfer);
        player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
    }

    /** 阶段状态机写入：按 requestId upsert；安全终态同步写入持久化墓碑。 */
    public static boolean upsertTransfer(net.minecraft.server.level.ServerPlayer player,
                                         com.tanrunn.stockmarket.api.BankTransferRecord transfer) {
        AccountData data = data(player);
        boolean ok = data.upsertTransfer(transfer);
        player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
        return ok;
    }

    /** Initializes or rolls the daily equity baseline for an online player. */
    public static void ensureDailyBaseline(net.minecraft.server.level.ServerPlayer player,
                                            long dayIndex, double currentValue) {
        AccountData data = data(player);
        if (data.dailyBaselineDay != dayIndex || !Double.isFinite(data.dailyBaselineValue)) {
            data.dailyBaselineDay = dayIndex;
            data.dailyBaselineValue = round2(currentValue);
            player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
        }
    }

    public static double dailyBaseline(net.minecraft.server.level.ServerPlayer player) {
        return data(player).dailyBaselineValue;
    }

    public static long dailyExternalCashFlowCents(
            net.minecraft.server.level.ServerPlayer player, long dayIndex) {
        return data(player).dailyExternalCashFlowCents(dayIndex);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static AccountData data(net.minecraft.server.level.ServerPlayer player) {
        return player.getData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get());
    }
}
