package com.tanrunn.stockmarket.server.market;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;
import com.tanrunn.stockmarket.api.TransactionRecord;
import com.tanrunn.stockmarket.common.TradeInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent per-player stock account, stored as a player attachment.
 */
public class AccountData implements INBTSerializable<CompoundTag> {
    public static final int SCHEMA_VERSION = 4;

    public int schemaVersion = SCHEMA_VERSION;
    public boolean initialized = false;
    public double cash = 0;
    public Map<String, Integer> holdings = new HashMap<>();
    /** Fee-inclusive acquisition cost for the currently available shares. */
    public Map<String, Double> costBasis = new HashMap<>();
    /** Realized P&L after selling shares, including trading fees. */
    public double realizedPnl = 0;
    /** Equity snapshot at the start of the current in-game day. */
    public long dailyBaselineDay = Long.MIN_VALUE;
    public double dailyBaselineValue = 0;
    /** Highest persisted corporate-action id already applied to this account. */
    public long lastCorporateActionId = 0;
    public List<TradeInfo> trades = new ArrayList<>();
    /** Cross-Mod cash deposits/withdrawals, retained for audit and idempotency. */
    public List<TransactionRecord> ledger = new ArrayList<>();

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schemaVersion", schemaVersion);
        tag.putBoolean("initialized", initialized);
        tag.putDouble("cash", cash);
        ListTag list = new ListTag();
        holdings.forEach((id, qty) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id);
            entry.putInt("qty", qty);
            list.add(entry);
        });
        tag.put("holdings", list);
        ListTag basisList = new ListTag();
        costBasis.forEach((id, basis) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id);
            entry.putDouble("basis", basis);
            basisList.add(entry);
        });
        tag.put("costBasis", basisList);
        tag.putDouble("realizedPnl", realizedPnl);
        tag.putLong("dailyBaselineDay", dailyBaselineDay);
        tag.putDouble("dailyBaselineValue", dailyBaselineValue);
        tag.putLong("lastCorporateActionId", lastCorporateActionId);
        ListTag tradeList = new ListTag();
        for (TradeInfo trade : trades) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("day", trade.dayIndex());
            entry.putString("stock", trade.stockId());
            entry.putBoolean("buy", trade.buy());
            entry.putDouble("price", trade.price());
            entry.putInt("qty", trade.quantity());
            entry.putDouble("fee", trade.fee());
            tradeList.add(entry);
        }
        tag.put("trades", tradeList);
        ListTag ledgerList = new ListTag();
        for (TransactionRecord transaction : ledger) {
            CompoundTag entry = new CompoundTag();
            entry.putString("transactionId", transaction.transactionId());
            entry.putString("requestId", transaction.requestId());
            entry.putLong("day", transaction.dayIndex());
            entry.putLong("deltaCents", transaction.deltaCents());
            entry.putLong("balanceCents", transaction.balanceCents());
            entry.putString("source", transaction.source());
            entry.putString("reason", transaction.reason());
            ledgerList.add(entry);
        }
        tag.put("ledger", ledgerList);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        schemaVersion = tag.getInt("schemaVersion");
        initialized = tag.getBoolean("initialized");
        cash = tag.getDouble("cash");
        holdings.clear();
        ListTag list = tag.getList("holdings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            holdings.put(entry.getString("id"), entry.getInt("qty"));
        }
        costBasis.clear();
        ListTag basisList = tag.getList("costBasis", Tag.TAG_COMPOUND);
        for (int i = 0; i < basisList.size(); i++) {
            CompoundTag entry = basisList.getCompound(i);
            costBasis.put(entry.getString("id"), entry.getDouble("basis"));
        }
        realizedPnl = tag.getDouble("realizedPnl");
        dailyBaselineDay = tag.contains("dailyBaselineDay", Tag.TAG_ANY_NUMERIC)
                ? tag.getLong("dailyBaselineDay") : Long.MIN_VALUE;
        dailyBaselineValue = tag.getDouble("dailyBaselineValue");
        lastCorporateActionId = tag.contains("lastCorporateActionId", Tag.TAG_ANY_NUMERIC)
                ? tag.getLong("lastCorporateActionId") : 0;
        schemaVersion = SCHEMA_VERSION;
        trades.clear();
        ListTag tradeList = tag.getList("trades", Tag.TAG_COMPOUND);
        for (int i = 0; i < tradeList.size(); i++) {
            CompoundTag entry = tradeList.getCompound(i);
            trades.add(new TradeInfo(entry.getLong("day"), entry.getString("stock"), entry.getBoolean("buy"),
                    entry.getDouble("price"), entry.getInt("qty"), entry.getDouble("fee")));
        }
        ledger.clear();
        ListTag ledgerList = tag.getList("ledger", Tag.TAG_COMPOUND);
        for (int i = 0; i < ledgerList.size(); i++) {
            CompoundTag entry = ledgerList.getCompound(i);
            ledger.add(new TransactionRecord(
                    entry.getString("transactionId"),
                    entry.getString("requestId"),
                    entry.getLong("day"),
                    entry.getLong("deltaCents"),
                    entry.getLong("balanceCents"),
                    entry.getString("source"),
                    entry.getString("reason")));
        }
        while (ledger.size() > 200) ledger.remove(ledger.size() - 1);
    }

    public HoldingAccount toView() {
        return new HoldingAccount(cash, holdings, costBasis, realizedPnl);
    }

    public void apply(HoldingAccount account) {
        this.cash = account.cash();
        this.holdings = new HashMap<>(account.holdings());
        this.costBasis = new HashMap<>(account.costBasis());
        this.realizedPnl = account.realizedPnl();
    }

    public void addTrade(TradeInfo trade) {
        trades.add(0, trade);
        while (trades.size() > 100) {
            trades.remove(trades.size() - 1);
        }
    }

    public void addTransaction(TransactionRecord transaction) {
        ledger.add(0, transaction);
        while (ledger.size() > 200) {
            ledger.remove(ledger.size() - 1);
        }
    }
}
