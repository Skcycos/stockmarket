package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.api.BankTransferPhase;
import com.tanrunn.stockmarket.api.BankTransferRecord;
import com.tanrunn.stockmarket.api.BankTransferRequest;
import com.tanrunn.stockmarket.api.BankTransferStatus;
import com.tanrunn.stockmarket.api.OperationIds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 银行 ⇄ 证券转账账本（AccountData.transfers + 持久化墓碑）的阶段持久化与 fail-closed 反例。 */
class BankTransferPersistenceTest {

    private static final String PROVIDER = "server_menu:lc_bank_main";
    private static final long EPOCH = 424242L;

    private static BankTransferRecord record(BankTransferPhase phase, BankTransferStatus status) {
        return new BankTransferRecord("req-1", BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                phase, status, "审计消息", 10, 1000, 1000, 10,
                "sm:bd:o", "sm:bc:o", "sm:sd:o", "sm:sc:o", "sm:rb:o", 5000, 2000,
                PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
    }

    private static BankTransferRecord roundTrip(BankTransferRecord original) {
        AccountData data = new AccountData();
        data.addTransfer(original);
        AccountData restored = new AccountData();
        restored.deserializeNBT(null, data.serializeNBT(null));
        return restored.transfers.get(0);
    }

    @Test
    void preparedPhaseSurvivesSerializationWithNewFields() {
        BankTransferRecord restored = roundTrip(record(BankTransferPhase.PREPARED,
                BankTransferStatus.INCOMPLETE_TRANSFER));
        assertEquals(BankTransferPhase.PREPARED, restored.phase());
        assertEquals(10, restored.copperAmount());
        assertEquals(1000, restored.actualDebitCents());
        assertEquals(PROVIDER, restored.providerId());
        assertEquals(OperationIds.VERSION, restored.operationIdVersion());
        assertEquals(BankTransferRecord.STATE_MACHINE_VERSION, restored.stateMachineVersion());
        assertEquals(EPOCH, restored.runtimeEpoch());
    }

    @Test
    void destinationCreditPendingAndCompensationPendingSurviveSerialization() {
        assertEquals(BankTransferPhase.DESTINATION_CREDIT_PENDING,
                roundTrip(record(BankTransferPhase.DESTINATION_CREDIT_PENDING,
                        BankTransferStatus.INCOMPLETE_TRANSFER)).phase());
        assertEquals(BankTransferPhase.COMPENSATION_PENDING,
                roundTrip(record(BankTransferPhase.COMPENSATION_PENDING,
                        BankTransferStatus.INCOMPLETE_TRANSFER)).phase());
    }

    @Test
    void completedPhaseSurvivesSerialization() {
        BankTransferRecord restored = roundTrip(record(BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS));
        assertEquals(BankTransferPhase.COMPLETED, restored.phase());
        assertEquals(BankTransferStatus.SUCCESS, restored.status());
    }

    @Test
    void oldVersionNonSafeTerminalFailsClosedToManualReview() {
        // 旧版记录（无 stateMachineVersion）：非安全终态（SOURCE_DEBITED）→ MANUAL_REVIEW。
        CompoundTag row = validDepositRow();
        row.remove("stateMachineVersion");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(row).phase());
    }

    @Test
    void oldVersionSafeTerminalKeptForReadOnlyReplay() {
        // 旧版安全终态（COMPLETED）允许只读重放：不被强制成 MANUAL_REVIEW。
        CompoundTag row = validDepositRow();
        row.putString("phase", "COMPLETED");
        row.putString("status", "SUCCESS");
        row.remove("stateMachineVersion");
        BankTransferRecord parsed = parseRow(row);
        assertEquals(BankTransferPhase.COMPLETED, parsed.phase());
        assertEquals(BankTransferStatus.SUCCESS, parsed.status());
    }

    @Test
    void malformedPhaseFailsClosedToManualReviewNotDepositSuccess() {
        CompoundTag row = validDepositRow();
        row.putString("phase", "NOT_A_PHASE");
        row.putString("status", "SUCCESS");
        BankTransferRecord parsed = parseRow(row);
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parsed.phase());
        assertEquals(BankTransferStatus.MANUAL_REVIEW, parsed.status());
        assertTrue(parsed.phase() != BankTransferPhase.COMPLETED);
    }

    // ---- 墓碑（v2 防重） ----

    @Test
    void safeTerminalWritesCreatePersistentTombstone() {
        AccountData data = new AccountData();
        data.upsertTransfer(record(BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS));
        assertEquals(1, data.transferTombstones.size());
        assertEquals("req-1", data.transferTombstones.get("req-1").requestId());
        assertNotNull(data.findByRequestId("req-1"));
    }

    @Test
    void tombstoneSurvivesSerializationAndStillBlocksDedup() {
        AccountData data = new AccountData();
        data.upsertTransfer(record(BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS));
        AccountData restored = new AccountData();
        restored.deserializeNBT(null, data.serializeNBT(null));
        assertEquals(1, restored.transferTombstones.size());
        assertNotNull(restored.findByRequestId("req-1"));
        assertEquals(BankTransferPhase.COMPLETED, restored.findByRequestId("req-1").phase());
    }

    @Test
    void evictingSafeTerminalFromDetailKeepsTombstone() {
        AccountData data = new AccountData();
        data.upsertTransfer(record(BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS));
        data.transfers.removeIf(t -> t.requestId().equals("req-1")); // 模拟从详细历史淘汰
        assertNotNull(data.findByRequestId("req-1"));
    }

    @Test
    void inFlightRecordsCannotBeEvictedAtCapacity() {
        AccountData data = new AccountData();
        for (int i = 0; i < AccountData.MAX_TRANSFER_RECORDS; i++) {
            BankTransferRecord inFlight = new BankTransferRecord("in-" + i,
                    BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                    BankTransferPhase.SOURCE_DEBITED, BankTransferStatus.INCOMPLETE_TRANSFER, "在途",
                    0, 0, 0, 0, "", "", "", "", "", 0, 0,
                    PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
            assertTrue(data.upsertTransfer(inFlight), "在途记录应能写入");
        }
        BankTransferRecord next = new BankTransferRecord("next",
                BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                BankTransferPhase.PREPARED, BankTransferStatus.INCOMPLETE_TRANSFER, "新请求",
                0, 0, 0, 0, "", "", "", "", "", 0, 0,
                PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
        assertFalse(data.upsertTransfer(next));
        assertNotNull(data.findByRequestId("in-" + (AccountData.MAX_TRANSFER_RECORDS - 1)));
    }

    @Test
    void malformedTombstoneNbtIsKeptNotDropped() {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        CompoundTag row = validDepositRow();
        row.putString("phase", "BOGUS_PHASE");
        list.add(row);
        CompoundTag tag = new CompoundTag();
        tag.put("transferTombstones", list);
        AccountData restored = new AccountData();
        restored.deserializeNBT(null, tag);
        assertEquals(1, restored.transferTombstones.size());
        String requestId = validDepositRow().getString("requestId");
        assertNotNull(restored.findByRequestId(requestId));
        assertEquals(BankTransferPhase.MANUAL_REVIEW,
                restored.transferTombstones.get(requestId).phase());
    }

    @Test
    void transfersStayBoundedButTombstonesGrow() {
        AccountData data = new AccountData();
        for (int i = 0; i < AccountData.MAX_TRANSFER_RECORDS + 50; i++) {
            BankTransferRecord completed = new BankTransferRecord("req-" + i,
                    BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                    BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, "转账成功",
                    0, 0, 0, 0, "", "", "", "", "", 0, 0,
                    PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
            assertTrue(data.upsertTransfer(completed));
        }
        assertTrue(data.transfers.size() <= AccountData.MAX_TRANSFER_RECORDS);
        assertEquals(AccountData.MAX_TRANSFER_RECORDS + 50, data.transferTombstones.size());
        assertNotNull(data.findByRequestId("req-0"));
    }

    // ---- NBT 整行畸形 → MANUAL_REVIEW ----

    private static CompoundTag row(String requestId, String direction, String phase, String status,
                                   String providerId, int version, int smv, long epoch,
                                   long reqCopper, long reqCents, long debit, long copper,
                                   String opBankDebit, String opBankCredit, String opSecDebit,
                                   String opSecCredit, String opRollback) {
        CompoundTag row = new CompoundTag();
        row.putString("requestId", requestId);
        row.putString("direction", direction);
        row.putString("phase", phase);
        row.putString("status", status);
        row.putString("providerId", providerId);
        row.putInt("operationIdVersion", version);
        row.putInt("stateMachineVersion", smv);
        row.putLong("runtimeEpoch", epoch);
        row.putLong("requestedCopper", reqCopper);
        row.putLong("requestedSecuritiesCents", reqCents);
        row.putLong("actualDebitCents", debit);
        row.putLong("copperAmount", copper);
        row.putString("opBankDebit", opBankDebit);
        row.putString("opBankCredit", opBankCredit);
        row.putString("opSecDebit", opSecDebit);
        row.putString("opSecCredit", opSecCredit);
        row.putString("opRollback", opRollback);
        row.putLong("bank", 0);
        row.putLong("securities", 0);
        return row;
    }

    private static CompoundTag validDepositRow() {
        return row("req-d", "DEPOSIT_TO_SECURITIES", "SOURCE_DEBITED", "INCOMPLETE_TRANSFER",
                PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH,
                10, 1000, 1000, 10, "sm:bd:o", "", "", "sm:sc:o", "sm:rb:o");
    }

    private static BankTransferRecord parseRow(CompoundTag row) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(row);
        CompoundTag tag = new CompoundTag();
        tag.put("transfers", list);
        AccountData restored = new AccountData();
        restored.deserializeNBT(null, tag);
        return restored.transfers.get(0);
    }

    @Test
    void invalidDirectionPlusSourceDebitedForcesManualReview() {
        CompoundTag r = validDepositRow();
        r.putString("direction", "BOGUS");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    @Test
    void missingDirectionForcesManualReview() {
        CompoundTag r = validDepositRow();
        r.remove("direction");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    @Test
    void validDirectionButMissingRequiredOpIdForcesManualReview() {
        CompoundTag r = validDepositRow();
        r.putString("opSecCredit", "");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    @Test
    void depositAmountInvariantBrokenForcesManualReview() {
        CompoundTag r = validDepositRow();
        r.putLong("requestedSecuritiesCents", 999);
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    @Test
    void withdrawRoundingRelationBrokenForcesManualReview() {
        CompoundTag r = row("req-w", "WITHDRAW_TO_BANK", "SOURCE_DEBITED", "INCOMPLETE_TRANSFER",
                PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH,
                0, 101, 100, 1, "", "sm:bc:o", "sm:sd:o", "", "sm:rb:o");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    @Test
    void completedWithContradictoryStatusForcesManualReview() {
        CompoundTag r = validDepositRow();
        r.putString("phase", "COMPLETED");
        r.putString("status", "BANK_ERROR");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    @Test
    void unknownOperationIdVersionForcesManualReview() {
        CompoundTag r = validDepositRow();
        r.putInt("operationIdVersion", 99);
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    @Test
    void missingProviderIdForcesManualReview() {
        CompoundTag r = validDepositRow();
        r.remove("providerId");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    @Test
    void missingEpochOnNonSafeTerminalForcesManualReview() {
        CompoundTag r = validDepositRow();
        r.remove("runtimeEpoch");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, parseRow(r).phase());
    }

    // ---- 第五轮：旧版安全终态迁移补建墓碑 / 审计字段 / 冲突 ----

    private static BankTransferRecord manualReviewRecord() {
        return new BankTransferRecord("mr-1", BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                BankTransferPhase.MANUAL_REVIEW, BankTransferStatus.MANUAL_REVIEW,
                "人工审计原因：证据不足", 10, 1000, 1000, 10,
                "sm:bd:mr1", "", "", "sm:sc:mr1", "sm:rb:mr1", 4990, 1234,
                PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
    }

    private static AccountData roundTripAccountData(AccountData data) {
        AccountData restored = new AccountData();
        restored.deserializeNBT(null, data.serializeNBT(null));
        return restored;
    }

    @Test
    void legacyCompletedDepositGetsTombstoneBackfilledAndBlocksAfterEviction() {
        // 旧版存档：只有详细 COMPLETED，没有 transferTombstones 字段。
        AccountData oldData = new AccountData();
        oldData.upsertTransfer(record(BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS));
        oldData.transferTombstones.clear(); // 模拟旧版存档确实没有墓碑
        AccountData restored = roundTripAccountData(oldData);
        assertEquals(1, restored.transferTombstones.size(), "反序列化必须为安全终态补建墓碑");
        // 再写 300 条新安全终态，使旧详细行被淘汰。
        for (int i = 0; i < AccountData.MAX_TRANSFER_RECORDS + 50; i++) {
            BankTransferRecord newer = new BankTransferRecord("new-" + i,
                    BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                    BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, "转账成功",
                    0, 0, 0, 0, "", "", "", "", "", 0, 0,
                    PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
            assertTrue(restored.upsertTransfer(newer));
        }
        // 旧 requestId 仍被墓碑挡住（详细列表已淘汰）。
        assertTrue(restored.transfers.stream().noneMatch(t -> t.requestId().equals("req-1")));
        BankTransferRecord found = restored.findByRequestId("req-1");
        assertNotNull(found);
        assertEquals(BankTransferPhase.COMPLETED, found.phase());
    }

    @Test
    void legacyWithdrawCompletedNeverRecreditsLcViaTombstone() {
        // 旧版出金 COMPLETED + 无墓碑 → 补建后按只读防重返回。
        BankTransferRecord wd = new BankTransferRecord("wd-old", BankTransferRequest.Direction.WITHDRAW_TO_BANK,
                BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, "提现成功",
                0, 101, 200, 2, "", "sm:bc:o", "sm:sd:o", "", "sm:rb:o", 2, 300,
                PROVIDER, OperationIds.VERSION, 0, 0); // 旧版：smv 与 epoch 缺失
        AccountData data = new AccountData();
        data.upsertTransfer(wd);
        data.transferTombstones.clear();
        AccountData restored = roundTripAccountData(data);
        BankTransferRecord found = restored.findByRequestId("wd-old");
        assertNotNull(found);
        assertEquals(BankTransferStatus.SUCCESS, found.status());
        // 不同金额重放 → 冲突由调用方（服务）按 REQUEST_CONFLICT 处理，这里只验证防重键存在。
        assertEquals(BankTransferPhase.COMPLETED, found.phase());
    }

    @Test
    void legacyPreparedClearFailureMigratesToRejected() {
        CompoundTag row = row("old-iff", "DEPOSIT_TO_SECURITIES", "PREPARED", "INSUFFICIENT_FUNDS",
                PROVIDER, OperationIds.VERSION, 0, 0,
                10, 1000, 1000, 10, "sm:bd:o", "", "", "sm:sc:o", "sm:rb:o");
        BankTransferRecord parsed = parseRow(row);
        assertEquals(BankTransferPhase.REJECTED, parsed.phase());
        assertEquals(BankTransferStatus.INSUFFICIENT_FUNDS, parsed.status());
    }

    @Test
    void manualReviewAuditFieldsSurviveNbtRoundTrip() {
        AccountData data = new AccountData();
        data.upsertTransfer(manualReviewRecord());
        // 淘汰详细行，只留墓碑。
        data.transfers.removeIf(t -> t.requestId().equals("mr-1"));
        AccountData restored = roundTripAccountData(data);
        BankTransferRecord tomb = restored.findByRequestId("mr-1");
        assertNotNull(tomb);
        assertEquals("人工审计原因：证据不足", tomb.message());
        assertEquals(4990, tomb.bankBalanceCopper());
        assertEquals(1234, tomb.securitiesBalanceCents());
        assertEquals("sm:bd:mr1", tomb.opBankDebit());
        assertEquals("sm:sc:mr1", tomb.opSecuritiesCredit());
        assertEquals(PROVIDER, tomb.providerId());
        assertEquals(BankTransferRecord.STATE_MACHINE_VERSION, tomb.stateMachineVersion());
        assertEquals(EPOCH, tomb.runtimeEpoch());
    }

    @Test
    void legacyTombstoneWithoutAuditFieldsGetsPlaceholderNotFabricatedBalances() {
        ListTag list = new ListTag();
        CompoundTag row = new CompoundTag();
        row.putString("requestId", "old-tomb");
        row.putString("direction", "DEPOSIT_TO_SECURITIES");
        row.putString("phase", "COMPLETED");
        row.putString("status", "SUCCESS");
        row.putLong("requestedCopper", 10);
        row.putLong("requestedSecuritiesCents", 1000);
        row.putLong("actualDebitCents", 1000);
        row.putLong("copperAmount", 10);
        // 没有 bank/securities/opId/providerId 等 → 旧审计缺失
        list.add(row);
        CompoundTag tag = new CompoundTag();
        tag.put("transferTombstones", list);
        AccountData restored = new AccountData();
        restored.deserializeNBT(null, tag);
        BankTransferRecord tomb = restored.findByRequestId("old-tomb");
        assertNotNull(tomb);
        assertTrue(tomb.message().contains("旧记录缺少审计详情"));
    }

    @Test
    void duplicateTombstoneDifferentFingerprintConflictsFailClosed() {
        ListTag list = new ListTag();
        CompoundTag first = validDepositRow(); // req-d / 10 铜币
        first.putString("phase", "COMPLETED");
        first.putString("status", "SUCCESS");
        CompoundTag second = new CompoundTag();
        second.putString("requestId", "req-d");
        second.putString("direction", "DEPOSIT_TO_SECURITIES");
        second.putString("phase", "COMPLETED");
        second.putString("status", "SUCCESS");
        second.putLong("requestedCopper", 99); // 不同金额指纹
        second.putLong("requestedSecuritiesCents", 9900);
        second.putLong("actualDebitCents", 9900);
        second.putLong("copperAmount", 99);
        second.putLong("bank", 0);
        second.putLong("securities", 0);
        list.add(first);
        list.add(second);
        CompoundTag tag = new CompoundTag();
        tag.put("transferTombstones", list);
        AccountData restored = new AccountData();
        restored.deserializeNBT(null, tag);
        // 冲突 → find 返回 MANUAL_REVIEW 阻断；原合法指纹保留在墓碑中供审计。
        assertTrue(restored.tombstoneConflicts.contains("req-d"));
        BankTransferRecord blocked = restored.findByRequestId("req-d");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, blocked.phase());
        assertEquals(com.tanrunn.stockmarket.api.BankTransferStatus.MANUAL_REVIEW, blocked.status());
        // 原合法指纹（10 铜币）不被覆盖。
        assertEquals(10, restored.transferTombstones.get("req-d").requestedCopper());
    }

    @Test
    void serializedNormalRecordRestoresFully() {
        AccountData data = new AccountData();
        data.addTransfer(record(BankTransferPhase.DESTINATION_CREDITED, BankTransferStatus.INCOMPLETE_TRANSFER));
        AccountData restored = new AccountData();
        restored.deserializeNBT(null, data.serializeNBT(null));
        BankTransferRecord parsed = restored.transfers.get(0);
        assertEquals(BankTransferPhase.DESTINATION_CREDITED, parsed.phase());
        assertEquals(10, parsed.copperAmount());
        assertEquals(1000, parsed.actualDebitCents());
        assertEquals(PROVIDER, parsed.providerId());
        assertEquals(OperationIds.VERSION, parsed.operationIdVersion());
        assertEquals(BankTransferRecord.STATE_MACHINE_VERSION, parsed.stateMachineVersion());
        assertEquals(EPOCH, parsed.runtimeEpoch());
        assertEquals("sm:bd:o", parsed.opBankDebit());
        assertEquals("sm:rb:o", parsed.opRollback());
    }

    @Test
    void oldSavesWithoutTransfersFieldLoadAsEmpty() {
        AccountData data = new AccountData();
        data.deserializeNBT(null, new CompoundTag());
        assertEquals(0, data.transfers.size());
        assertEquals(0, data.transferTombstones.size());
    }
}
