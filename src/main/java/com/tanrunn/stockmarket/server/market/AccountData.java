package com.tanrunn.stockmarket.server.market;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;
import com.tanrunn.stockmarket.api.BankTransferRecord;
import com.tanrunn.stockmarket.api.BankTransferRequest;
import com.tanrunn.stockmarket.api.BankTransferService;
import com.tanrunn.stockmarket.api.BankTransferStatus;
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
    /** 银行 ⇄ 证券转账账本上限（有限历史容量；超出淘汰最早记录，不宣称永久防重）。 */
    public static final int MAX_TRANSFER_RECORDS = 256;

    public List<TradeInfo> trades = new ArrayList<>();
    /** Cross-Mod cash deposits/withdrawals, retained for audit and idempotency. */
    public List<TransactionRecord> ledger = new ArrayList<>();
    /** 银行 ⇄ 证券转账记录（请求标识/方向/金额/状态），用于幂等重放与人工审计。 */
    public List<com.tanrunn.stockmarket.api.BankTransferRecord> transfers = new ArrayList<>();
    /**
     * 持久化防重墓碑（requestId → 安全终态记录）：唯一、不可淘汰的防重依据。
     * 记录达到{@link #MAX_TRANSFER_RECORDS}从 transfers 淘汰时，防重信息保留在此，
     * 旧 requestId 绝不会再被当作新请求；NBT 反序列化时畸形墓碑也保留为 MANUAL_REVIEW
     * （不绕过防重）。
     */
    public final java.util.LinkedHashMap<String, com.tanrunn.stockmarket.api.BankTransferRecord>
            transferTombstones = new java.util.LinkedHashMap<>();
    /** 墓碑冲突（同一 requestId 出现不同防重指纹）：该 requestId 强制 MANUAL_REVIEW（不绕过防重）。 */
    public final java.util.LinkedHashSet<String> tombstoneConflicts = new java.util.LinkedHashSet<>();

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
        ListTag transferList = new ListTag();
        for (com.tanrunn.stockmarket.api.BankTransferRecord transfer : transfers) {
            CompoundTag entry = new CompoundTag();
            entry.putString("requestId", transfer.requestId());
            entry.putString("direction", transfer.direction().name());
            entry.putString("phase", transfer.phase().name());
            entry.putString("status", transfer.status().name());
            entry.putString("message", transfer.message());
            entry.putLong("requestedCopper", transfer.requestedCopper());
            entry.putLong("requestedSecuritiesCents", transfer.requestedSecuritiesCents());
            entry.putLong("actualDebitCents", transfer.actualDebitCents());
            entry.putLong("copperAmount", transfer.copperAmount());
            entry.putString("opBankDebit", nz(transfer.opBankDebit()));
            entry.putString("opBankCredit", nz(transfer.opBankCredit()));
            entry.putString("opSecDebit", nz(transfer.opSecuritiesDebit()));
            entry.putString("opSecCredit", nz(transfer.opSecuritiesCredit()));
            entry.putString("opRollback", nz(transfer.opRollback()));
            entry.putLong("bank", transfer.bankBalanceCopper());
            entry.putLong("securities", transfer.securitiesBalanceCents());
            entry.putString("providerId", nz(transfer.providerId()));
            entry.putInt("operationIdVersion", transfer.operationIdVersion());
            entry.putInt("stateMachineVersion", transfer.stateMachineVersion());
            entry.putLong("runtimeEpoch", transfer.runtimeEpoch());
            transferList.add(entry);
        }
        tag.put("transfers", transferList);
        ListTag tombstoneList = new ListTag();
        for (com.tanrunn.stockmarket.api.BankTransferRecord tombstone : transferTombstones.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("requestId", tombstone.requestId());
            entry.putString("direction", tombstone.direction().name());
            entry.putString("phase", tombstone.phase().name());
            entry.putString("status", tombstone.status().name());
            entry.putLong("requestedCopper", tombstone.requestedCopper());
            entry.putLong("requestedSecuritiesCents", tombstone.requestedSecuritiesCents());
            entry.putLong("actualDebitCents", tombstone.actualDebitCents());
            entry.putLong("copperAmount", tombstone.copperAmount());
            entry.putString("opBankDebit", nz(tombstone.opBankDebit()));
            entry.putString("opBankCredit", nz(tombstone.opBankCredit()));
            entry.putString("opSecDebit", nz(tombstone.opSecuritiesDebit()));
            entry.putString("opSecCredit", nz(tombstone.opSecuritiesCredit()));
            entry.putString("opRollback", nz(tombstone.opRollback()));
            entry.putString("providerId", nz(tombstone.providerId()));
            entry.putInt("operationIdVersion", tombstone.operationIdVersion());
            entry.putInt("stateMachineVersion", tombstone.stateMachineVersion());
            entry.putLong("runtimeEpoch", tombstone.runtimeEpoch());
            entry.putString("message", cap(tombstone.message()));
            entry.putLong("bank", tombstone.bankBalanceCopper());
            entry.putLong("securities", tombstone.securitiesBalanceCents());
            tombstoneList.add(entry);
        }
        tag.put("transferTombstones", tombstoneList);
        net.minecraft.nbt.ListTag conflictList = new net.minecraft.nbt.ListTag();
        for (String conflict : tombstoneConflicts) {
            conflictList.add(net.minecraft.nbt.StringTag.valueOf(conflict));
        }
        tag.put("tombstoneConflicts", conflictList);
        return tag;
    }

    private static String cap(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
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
        transfers.clear();
        transferTombstones.clear();
        tombstoneConflicts.clear();
        if (tag.contains("transfers", Tag.TAG_LIST)) {
            ListTag transferList = tag.getList("transfers", Tag.TAG_COMPOUND);
            for (int i = 0; i < transferList.size(); i++) {
                transfers.add(backfillTombstone(parseTransferRow(transferList.getCompound(i))));
            }
        }
        if (tag.contains("transferTombstones", Tag.TAG_LIST)) {
            ListTag tombstoneList = tag.getList("transferTombstones", Tag.TAG_COMPOUND);
            for (int i = 0; i < tombstoneList.size(); i++) {
                com.tanrunn.stockmarket.api.BankTransferRecord parsed = parseTransferRow(tombstoneList.getCompound(i));
                String rid = parsed.requestId();
                if (rid == null || rid.isBlank()) {
                    continue;
                }
                com.tanrunn.stockmarket.api.BankTransferRecord existing = transferTombstones.get(rid);
                if (existing == null) {
                    // 防重不丢失：畸形墓碑也保留（强制 MANUAL_REVIEW），绝不静默删除让旧 requestId 可重放。
                    transferTombstones.put(rid, parsed);
                } else if (!existing.dedupMatches(parsed)) {
                    // 同一 requestId 出现不同防重指纹：fail closed，保留原合法指纹，
                    // 标记冲突（find 返回 MANUAL_REVIEW 阻断）。
                    tombstoneConflicts.add(rid);
                }
            }
        }
        if (tag.contains("tombstoneConflicts", Tag.TAG_LIST)) {
            ListTag conflictList = tag.getList("tombstoneConflicts", Tag.TAG_STRING);
            for (int i = 0; i < conflictList.size(); i++) {
                tombstoneConflicts.add(conflictList.getString(i));
            }
        }
    }

    /**
     * 反序列化详细转账行时的墓碑补建（第五轮）：
     * 所有安全终态（COMPLETED/COMPENSATED/COMPENSATION_FAILED/MANUAL_REVIEW/REJECTED）
     * 必须已有持久墓碑（旧版存档没有墓碑 → 自动补建），否则详细记录一旦被淘汰，
     * 旧 requestId 将失去防重。旧版非安全终态已被强制 MANUAL_REVIEW（parseTransferRow），
     * 同样补墓碑阻断。返回补建后的记录（供加入详细列表）。
     */
    private com.tanrunn.stockmarket.api.BankTransferRecord backfillTombstone(
            com.tanrunn.stockmarket.api.BankTransferRecord parsed) {
        String rid = parsed.requestId();
        if (rid == null || rid.isBlank()) {
            return parsed;
        }
        if (parsed.isSafeTerminal()) {
            com.tanrunn.stockmarket.api.BankTransferRecord existing = transferTombstones.get(rid);
            if (existing == null) {
                transferTombstones.put(rid, parsed);
            } else if (!existing.dedupMatches(parsed)) {
                // 详细记录与已有墓碑指纹冲突：标记冲突（find 返回 MANUAL_REVIEW 阻断，资金 0）。
                tombstoneConflicts.add(rid);
            }
        }
        return parsed;
    }

    /**
     * 转账行整行解析（第三轮修复）：任何畸形都<b>整行强制 MANUAL_REVIEW</b>——
     * 保留原行用于管理员审计，但绝不自动动账、绝不默认成入金成功。畸形判断覆盖：
     * direction/phase/status 缺失或非法、requestId 空或超长、providerId 缺失、
     * operationIdVersion 非法、runtimeEpoch 缺失（且非安全终态）、必填 opId 缺失/
     * opId 超长/前缀不符、金额为负/不变量不符/溢出、阶段状态组合非法。
     */
    private static com.tanrunn.stockmarket.api.BankTransferRecord parseTransferRow(CompoundTag entry) {
        boolean malformed = false;

        String requestId = entry.getString("requestId");
        if (requestId == null || requestId.isBlank()
                || requestId.length() > com.tanrunn.stockmarket.api.BankTransferRequest.MAX_REQUEST_ID_LENGTH) {
            malformed = true;
        }

        com.tanrunn.stockmarket.api.BankTransferRequest.Direction direction = null;
        try {
            direction = com.tanrunn.stockmarket.api.BankTransferRequest.Direction
                    .valueOf(entry.getString("direction"));
        } catch (RuntimeException e) {
            malformed = true;
            direction = com.tanrunn.stockmarket.api.BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES;
        }

        com.tanrunn.stockmarket.api.BankTransferPhase phase = safePhase(entry.getString("phase"));
        com.tanrunn.stockmarket.api.BankTransferStatus status = safeStatus(entry.getString("status"));
        // 旧版「PREPARED + 明确未动账失败」→ 安全迁移为 REJECTED（保持原失败状态）。
        if (phase == com.tanrunn.stockmarket.api.BankTransferPhase.PREPARED
                && com.tanrunn.stockmarket.api.BankTransferRecordValidator.isRejectedEligible(status)) {
            phase = com.tanrunn.stockmarket.api.BankTransferPhase.REJECTED;
        }
        // 旧版 COMPENSATION_FAILED / 金额不确定绝不迁移（保持原状，由 Validator fail closed）。

        String providerId = entry.getString("providerId");
        if (providerId == null || providerId.isBlank()) {
            malformed = true;
        }
        int operationIdVersion = entry.contains("operationIdVersion")
                ? entry.getInt("operationIdVersion") : 0;
        if (operationIdVersion != com.tanrunn.stockmarket.api.OperationIds.VERSION) {
            malformed = true;
        }
        int stateMachineVersion = entry.contains("stateMachineVersion")
                ? entry.getInt("stateMachineVersion") : 0;
        if (stateMachineVersion != com.tanrunn.stockmarket.api.BankTransferRecord.STATE_MACHINE_VERSION) {
            // 旧版：安全终态可只读重放（由 Validator 放宽），非安全终态在此按 malformed 处理。
        }
        long runtimeEpoch = entry.contains("runtimeEpoch") ? entry.getLong("runtimeEpoch")
                : com.tanrunn.stockmarket.api.BankTransferRecord.UNKNOWN_EPOCH;

        long requestedCopper = entry.getLong("requestedCopper");
        long requestedSecuritiesCents = entry.getLong("requestedSecuritiesCents");
        long actualDebitCents = entry.getLong("actualDebitCents");
        long copperAmount = entry.getLong("copperAmount");

        String message = entry.getString("message");
        String legacyNote = legacyAuditNote(entry);
        if (!legacyNote.isEmpty()) {
            message = (message == null || message.isEmpty()) ? legacyNote : message + " · " + legacyNote;
        }
        com.tanrunn.stockmarket.api.BankTransferRecord candidate = new com.tanrunn.stockmarket.api.BankTransferRecord(
                requestId, direction, phase, status, message,
                requestedCopper, requestedSecuritiesCents, actualDebitCents, copperAmount,
                entry.getString("opBankDebit"), entry.getString("opBankCredit"),
                entry.getString("opSecDebit"), entry.getString("opSecCredit"),
                entry.getString("opRollback"), entry.getLong("bank"), entry.getLong("securities"),
                providerId, operationIdVersion, stateMachineVersion, runtimeEpoch);

        if (!com.tanrunn.stockmarket.api.BankTransferRecordValidator.isWellFormed(candidate)) {
            malformed = true;
        }
        if (malformed) {
            candidate = new com.tanrunn.stockmarket.api.BankTransferRecord(
                    requestId, candidate.direction(),
                    com.tanrunn.stockmarket.api.BankTransferPhase.MANUAL_REVIEW,
                    com.tanrunn.stockmarket.api.BankTransferStatus.MANUAL_REVIEW,
                    cap(safeMessage(candidate.message())),
                    candidate.requestedCopper(), candidate.requestedSecuritiesCents(),
                    candidate.actualDebitCents(), candidate.copperAmount(),
                    candidate.opBankDebit(), candidate.opBankCredit(),
                    candidate.opSecuritiesDebit(), candidate.opSecuritiesCredit(),
                    candidate.opRollback(), candidate.bankBalanceCopper(),
                    candidate.securitiesBalanceCents(), candidate.providerId(),
                    candidate.operationIdVersion(), candidate.stateMachineVersion(),
                    candidate.runtimeEpoch());
        }
        return candidate;
    }

    private static String safeMessage(String message) {
        String base = message == null ? "" : message;
        return base + (base.isEmpty() ? "" : " · ") + "数据异常，需人工审计";
    }

    private static String legacyAuditNote(CompoundTag entry) {
        String base = "";
        if (!entry.contains("bank", Tag.TAG_ANY_NUMERIC) && !entry.contains("securities", Tag.TAG_ANY_NUMERIC)) {
            base = "旧记录缺少审计详情（余额/原因）";
        }
        return base;
    }

    private static BankTransferRequest.Direction safeDirection(String name) {
        try {
            return BankTransferRequest.Direction.valueOf(name);
        } catch (RuntimeException e) {
            // 方向损坏：方向本身仅作审计展示，真正的 fail closed 由 MANUAL_REVIEW 阶段保证。
            return BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES;
        }
    }

    private static BankTransferStatus safeStatus(String name) {
        try {
            return BankTransferStatus.valueOf(name);
        } catch (RuntimeException e) {
            return BankTransferStatus.MANUAL_REVIEW;
        }
    }

    private static com.tanrunn.stockmarket.api.BankTransferPhase safePhase(String name) {
        try {
            return com.tanrunn.stockmarket.api.BankTransferPhase.valueOf(name);
        } catch (RuntimeException e) {
            return com.tanrunn.stockmarket.api.BankTransferPhase.MANUAL_REVIEW;
        }
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
    }

    /**
     * Returns the net same-day cash flow caused by the LC bank bridge.
     * Deposits are positive and withdrawals are negative; this flow is not
     * investment profit and must be excluded from daily P&amp;L.
     */
    public long dailyExternalCashFlowCents(long dayIndex) {
        long flow = 0;
        for (TransactionRecord transaction : ledger) {
            if (transaction.dayIndex() != dayIndex
                    || !BankTransferService.SOURCE.equals(transaction.source())) {
                continue;
            }
            flow = Math.addExact(flow, transaction.deltaCents());
        }
        return flow;
    }

    public void addTransfer(com.tanrunn.stockmarket.api.BankTransferRecord transfer) {
        transfers.add(0, transfer);
    }

    /**
     * 阶段状态机 upsert（v2）：按 requestId 替换已有行；新请求插入头部。
     * 安全终态一律同时写入持久化墓碑（防重依据不丢失）。
     *
     * @return 是否落盘成功；false 表示容量已满且队尾为不可淘汰的在途记录：
     *         调用方必须在新转账的 PREPARED 阶段 fail closed（绝不动账）。
     */
    public boolean upsertTransfer(com.tanrunn.stockmarket.api.BankTransferRecord transfer) {
        if (transfer == null) {
            return true;
        }
        if (transfer.requestId() == null || transfer.requestId().isBlank()) {
            return false; // 无 requestId 无法建立防重
        }
        for (int i = 0; i < transfers.size(); i++) {
            if (transfer.requestId().equals(transfers.get(i).requestId())) {
                transfers.set(i, transfer);
                if (transfer.isSafeTerminal()) {
                    transferTombstones.put(transfer.requestId(), transfer);
                }
                return true;
            }
        }
        // 新记录：只在“墓碑已存在且指纹一致”的安全终态队尾下腾位。
        while (transfers.size() >= MAX_TRANSFER_RECORDS) {
            com.tanrunn.stockmarket.api.BankTransferRecord last = transfers.get(transfers.size() - 1);
            if (last == null) {
                return false;
            }
            com.tanrunn.stockmarket.api.BankTransferRecord tomb = transferTombstones.get(last.requestId());
            if (last.isSafeTerminal() && tomb != null && tomb.dedupMatches(last)
                    && !tombstoneConflicts.contains(last.requestId())) {
                // 墓碑存在、方向金额指纹一致、且当前持久状态已含墓碑 → 允许删除详细行。
                transfers.remove(transfers.size() - 1);
                continue;
            }
            // 墓碑缺失/指纹冲突/在途/审计中：绝不淘汰 → fail closed（不新增、不动账）。
            return false;
        }
        transfers.add(0, transfer);
        if (transfer.isSafeTerminal()) {
            transferTombstones.put(transfer.requestId(), transfer);
        }
        return true;
    }

    /** 防重查找：详细账本优先，其次持久化墓碑（旧 requestId 依旧被挡住）。 */
    public com.tanrunn.stockmarket.api.BankTransferRecord findByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        if (tombstoneConflicts.contains(requestId)) {
            // 同一 requestId 防重指纹冲突：fail closed，返回 MANUAL_REVIEW 阻断，保留原指纹审计。
            com.tanrunn.stockmarket.api.BankTransferRecord any = transferTombstones.get(requestId);
            return new com.tanrunn.stockmarket.api.BankTransferRecord(requestId,
                    any == null ? com.tanrunn.stockmarket.api.BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES
                            : any.direction(),
                    com.tanrunn.stockmarket.api.BankTransferPhase.MANUAL_REVIEW,
                    com.tanrunn.stockmarket.api.BankTransferStatus.MANUAL_REVIEW,
                    "同一请求标识存在冲突的防重指纹，需人工审计",
                    any == null ? 0 : any.requestedCopper(),
                    any == null ? 0 : any.requestedSecuritiesCents(),
                    any == null ? 0 : any.actualDebitCents(),
                    any == null ? 0 : any.copperAmount(),
                    "", "", "", "", "", 0, 0,
                    any == null ? "" : any.providerId(),
                    com.tanrunn.stockmarket.api.OperationIds.VERSION,
                    com.tanrunn.stockmarket.api.BankTransferRecord.STATE_MACHINE_VERSION,
                    any == null ? com.tanrunn.stockmarket.api.BankTransferRecord.UNKNOWN_EPOCH
                            : any.runtimeEpoch());
        }
        for (com.tanrunn.stockmarket.api.BankTransferRecord transfer : transfers) {
            if (requestId.equals(transfer.requestId())) {
                return transfer;
            }
        }
        return transferTombstones.get(requestId);
    }
}
