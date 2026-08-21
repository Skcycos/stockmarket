package com.tanrunn.stockmarket.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BankTransferService}（v2 write-ahead 状态机）完整测试。
 *
 * <p>覆盖：1 铜币=1 证券资金换算、write-ahead 阶段顺序、补偿（先落 COMPENSATION_PENDING）、
 * 资金崩溃反例（补偿已成功但未落 COMPENSATED）、同 runtimeEpoch + LC LRU 淘汰不再自动调 LC、
 * 阶段驱动恢复（DESTINATION_CREDIT_PENDING/COMPENSATION_PENDING 目标证券可重试、目标 LC 一律
 * MANUAL_REVIEW）、PREPARED 明确未动账失败白名单、256 条后墓碑防重。</p>
 */
class BankTransferServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final long MAX_COPPER = 9_000_000_000_000_000L;
    private static final long MAX_CENTS = 9_000_000_000_000_000_000L;
    private static final String PROVIDER = "server_menu:lc_bank_main";
    private static final long RUNTIME_EPOCH = 424242L;

    private final FakeBank bank = new FakeBank();
    private final FakeSecurities securities = new FakeSecurities();
    private final FakeLedger ledger = new FakeLedger();
    private final BankTransferService service =
            new BankTransferService(bank, securities, ledger, MAX_COPPER, MAX_CENTS, RUNTIME_EPOCH);

    // ---------------------------------------------------------------- 经济比例（不回退）

    @Test
    void depositOneCopperAddsOneDisplay() {
        bank.setBalance(1000);
        securities.setBalance(1000);
        BankTransferResult r = service.transfer(PLAYER, deposit(1));
        assertTrue(r.success());
        assertEquals(999, bank.balanceCopper());
        assertEquals(1100, securities.balanceCents()); // 显示 +1.00
    }

    @Test
    void depositTenCopperAddsTenDisplay() {
        bank.setBalance(5000);
        securities.setBalance(1000);
        assertTrue(service.transfer(PLAYER, deposit(10)).success());
        assertEquals(4990, bank.balanceCopper());
        assertEquals(2000, securities.balanceCents());
    }

    @Test
    void depositHundredCopperAddsHundredDisplay() {
        bank.setBalance(50_000);
        securities.setBalance(1000);
        assertTrue(service.transfer(PLAYER, deposit(100)).success());
        assertEquals(49_900, bank.balanceCopper());
        assertEquals(11_000, securities.balanceCents());
    }

    @Test
    void withdrawRoundsUpAndDebitsRoundedAmount() {
        bank.setBalance(0);
        securities.setBalance(500);
        BankTransferResult r = service.transfer(PLAYER, withdraw(101));
        assertTrue(r.success());
        assertEquals(2, bank.balanceCopper());
        assertEquals(300, securities.balanceCents()); // 扣 200
        assertEquals(200, r.actualDebitCents());

        securities.setBalance(500);
        bank.setBalance(0);
        assertTrue(service.transfer(PLAYER, withdraw(200)).success());
        assertEquals(2, bank.balanceCopper());
        assertEquals(300, securities.balanceCents());
    }

    @Test
    void withdrawRoundedUpInsufficientBalanceRejected() {
        bank.setBalance(0);
        securities.setBalance(150);
        assertEquals(BankTransferStatus.INSUFFICIENT_FUNDS, service.transfer(PLAYER, withdraw(101)).status());
        assertEquals(0, securities.withdrawCalls);
    }

    @Test
    void depositOverflowRejected() {
        bank.setBalance(Long.MAX_VALUE);
        assertEquals(BankTransferStatus.INVALID_AMOUNT,
                service.transfer(PLAYER, deposit(Long.MAX_VALUE / 100 + 1)).status());
        assertEquals(0, bank.withdrawCalls);
    }

    @Test
    void invalidRequestRejected() {
        assertEquals(BankTransferStatus.INVALID_AMOUNT, service.transfer(PLAYER, deposit(0)).status());
        assertEquals(BankTransferStatus.INVALID_AMOUNT, service.transfer(PLAYER, withdraw(0)).status());
        assertEquals(BankTransferStatus.INVALID_REQUEST,
                service.transfer(PLAYER, new BankTransferRequest(
                        BankTransferRequest.Direction.WITHDRAW_TO_BANK, 5, 100, "both")).status());
    }

    @Test
    void bridgeUnavailableFailsClosedBeforeAnyRecord() {
        bank.setAvailable(false);
        assertEquals(BankTransferStatus.UNAVAILABLE, service.transfer(PLAYER, deposit(10)).status());
        assertEquals(0, bank.withdrawCalls);
        // 无记录：桥恢复后重试是新请求（合理）。
    }

    // ---------------------------------------------------------------- write-ahead 阶段顺序

    @Test
    void depositWritesPendingBeforeSecuritiesCredit() {
        bank.setBalance(5000);
        securities.setBalance(1000);
        assertTrue(service.transfer(PLAYER, deposit(10)).success());
        assertTrue(ledger.historyHasPhase(BankTransferPhase.DESTINATION_CREDIT_PENDING));
        assertTrue(ledger.historyIndexOf(BankTransferPhase.DESTINATION_CREDIT_PENDING)
                < ledger.historyIndexOf(BankTransferPhase.DESTINATION_CREDITED));
    }

    @Test
    void withdrawWritesPendingBeforeLcDeposit() {
        bank.setBalance(0);
        securities.setBalance(500);
        assertTrue(service.transfer(PLAYER, withdraw(101)).success());
        assertTrue(ledger.historyHasPhase(BankTransferPhase.DESTINATION_CREDIT_PENDING));
    }

    // ---------------------------------------------------------------- 补偿（先 COMPENSATION_PENDING）

    @Test
    void depositSecuritiesFailCompensatesBankWithPendingFirst() {
        bank.setBalance(5000);
        securities.setBalance(1000);
        securities.setCreditFails(true);
        BankTransferResult r = service.transfer(PLAYER, deposit(10));
        assertEquals(BankTransferStatus.SECURITIES_ERROR, r.status());
        assertFalse(r.success());
        assertEquals(5000, bank.balanceCopper()); // 净退回
        assertTrue(ledger.historyHasPhase(BankTransferPhase.COMPENSATION_PENDING));
        assertEquals(BankTransferPhase.COMPENSATED, ledger.find(PLAYER, r.requestId()).phase());
    }

    @Test
    void depositCompensationFailsEndsCompensationFailed() {
        bank.setBalance(5000);
        securities.setBalance(1000);
        securities.setCreditFails(true);
        bank.setDepositFails(true);
        BankTransferResult r = service.transfer(PLAYER, deposit(10));
        assertEquals(BankTransferStatus.COMPENSATION_FAILED, r.status());
        assertEquals(4990, bank.balanceCopper());
    }

    @Test
    void withdrawBankFailCompensatesSecuritiesWithPendingFirst() {
        bank.setBalance(0);
        securities.setBalance(500);
        bank.setDepositFails(true);
        BankTransferResult r = service.transfer(PLAYER, withdraw(101));
        assertEquals(BankTransferStatus.BANK_ERROR, r.status());
        assertEquals(500, securities.balanceCents()); // 完整冲回
        assertTrue(ledger.historyHasPhase(BankTransferPhase.COMPENSATION_PENDING));
    }

    @Test
    void withdrawCompensationFailsEndsCompensationFailed() {
        bank.setBalance(0);
        securities.setBalance(500);
        bank.setDepositFails(true);
        securities.setCreditFails(true);
        BankTransferResult r = service.transfer(PLAYER, withdraw(101));
        assertEquals(BankTransferStatus.COMPENSATION_FAILED, r.status());
        assertEquals(300, securities.balanceCents());
    }

    // ---------------------------------------------------------------- 崩溃窗口反例（第六节）

    @Test
    void depositCompensatedBeforePersistedCrashDoesNotRecreditSecurities() {
        // 入金：LC 已扣 → 证券失败 → LC 补偿成功 → 未写 COMPENSATED 崩溃。
        // 残留：COMPENSATION_PENDING（入金）；LC 已扣且已退（用账本记录内的 opId 种入 fake）。
        BankTransferRequest req = deposit(10);
        bank.setBalance(5000);
        securities.setBalance(1000);
        ledger.write(PLAYER, compensationPendingRecord(req, BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                4990, 1000));
        int bankDepositsBefore = bank.depositCalls;
        int secDepositsBefore = securities.depositCalls;
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status(), "补偿目标为 LC，跨崩溃不可自动恢复");
        assertEquals(bankDepositsBefore, bank.depositCalls, "不得再次调用 LC");
        assertEquals(secDepositsBefore, securities.depositCalls, "不得向证券入账（无双重资产）");
        assertEquals(5000, bank.balanceCopper());
    }

    @Test
    void withdrawCompensatedBeforePersistedCrashDoesNotDoubleCompensate() {
        // 出金：证券已扣 → LC 失败 → 证券补偿成功 → 未写 COMPENSATED 崩溃。
        BankTransferRequest req = withdraw(101);
        bank.setBalance(0);
        securities.setBalance(500);
        securities.seedCredit("sm:rb:o", 200); // 证券补偿已应用（账本内 opRollback）
        ledger.write(PLAYER, compensationPendingRecord(req, BankTransferRequest.Direction.WITHDRAW_TO_BANK,
                0, 300));
        int secDepositsBefore = securities.depositCalls;
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.BANK_ERROR, r.status()); // COMPENSATED 只读重放
        assertTrue(r.duplicate());
        assertEquals(secDepositsBefore, securities.depositCalls, "证券补偿被持久去重，不重复");
        assertEquals(0, bank.depositCalls, "不得调用 LC");
        assertEquals(500, securities.balanceCents()); // 无双重资产
    }

    // ---------------------------------------------------------------- 同 epoch + LC LRU 淘汰（第二节）

    @Test
    void sameEpochLcLruEvictionMustNotAutoRecoverLcDeposit() {
        // 即使同 runtimeEpoch，也绝不自动恢复向 LC 的 deposit。
        BankTransferRequest req = withdraw(101);
        bank.setBalance(0).setCapacity(1); // LC 内存账本容量极小 → opId 可能被淘汰
        securities.setBalance(300);
        ledger.write(PLAYER, destinationCreditPendingRecord(req, BankTransferRequest.Direction.WITHDRAW_TO_BANK,
                0, 300));
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status(), "同 runtimeEpoch 不能证明 LC opId 仍在");
        assertEquals(0, bank.depositCalls);
        assertEquals(0, bank.balanceCopper());
    }

    @Test
    void sameEpochCompensationTargetLcIsManualReview() {
        BankTransferRequest req = deposit(10);
        ledger.write(PLAYER, compensationPendingRecord(req, BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                5000, 1000));
        bank.setBalance(5000);
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.depositCalls);
        assertEquals(0, securities.depositCalls);
    }

    // ---------------------------------------------------------------- DESTINATION_CREDIT_PENDING 恢复

    @Test
    void destinationCreditPendingDepositRecoversSecuritiesIdempotently() {
        BankTransferRequest req = deposit(10);
        bank.setBalance(4990);
        securities.setBalance(1000);
        securities.seedCredit("sm:sc:o", 1000); // 证券入账已应用（账本内 opSecuritiesCredit）
        ledger.write(PLAYER, destinationCreditPendingRecord(req, BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                4990, 1000));
        int secDepositsBefore = securities.depositCalls;
        BankTransferResult r = service.transfer(PLAYER, req);
        assertTrue(r.success(), "目标证券 + 持久幂等 opId → 允许自动恢复");
        assertTrue(r.duplicate());
        assertEquals(secDepositsBefore, securities.depositCalls, "持久去重，不重复入账");
        assertEquals(BankTransferPhase.COMPLETED, ledger.find(PLAYER, req.requestId()).phase());
    }

    @Test
    void destinationCreditPendingWithdrawTargetLcIsManualReview() {
        BankTransferRequest req = withdraw(101);
        ledger.write(PLAYER, destinationCreditPendingRecord(req, BankTransferRequest.Direction.WITHDRAW_TO_BANK,
                0, 300));
        bank.setBalance(0);
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.depositCalls);
        assertEquals(0, securities.depositCalls);
    }

    @Test
    void destinationCreditPendingOldVersionIsManualReview() {
        BankTransferRequest req = deposit(10);
        BankTransferRecord oldV = copyWithVersion(
                destinationCreditPendingRecord(req, BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                        4990, 1000),
                BankTransferRecord.STATE_MACHINE_VERSION - 1);
        ledger.write(PLAYER, oldV);
        bank.setBalance(4990);
        securities.setBalance(1000);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, service.transfer(PLAYER, req).status());
        assertEquals(0, securities.depositCalls);
    }

    // ---------------------------------------------------------------- COMPENSATION_PENDING 恢复

    @Test
    void compensationPendingWithdrawRecoversSecuritiesIdempotently() {
        BankTransferRequest req = withdraw(101);
        bank.setBalance(0);
        securities.setBalance(300); // 证券已扣 200（SOURCE_DEBITED 残留）
        ledger.write(PLAYER, compensationPendingRecord(req, BankTransferRequest.Direction.WITHDRAW_TO_BANK,
                0, 300));
        int secDepositsBefore = securities.depositCalls;
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.BANK_ERROR, r.status()); // COMPENSATED
        assertTrue(r.duplicate());
        assertEquals(secDepositsBefore + 1, securities.depositCalls, "补偿重试一次");
        assertEquals(500, securities.balanceCents()); // 完整冲回
        assertEquals(0, bank.depositCalls);
    }

    @Test
    void compensationPendingWithMissingOpIdIsManualReview() {
        BankTransferRequest req = withdraw(101);
        BankTransferRecord missing = copyWithOpRollback(
                compensationPendingRecord(req, BankTransferRequest.Direction.WITHDRAW_TO_BANK, 0, 300), "");
        ledger.write(PLAYER, missing);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, service.transfer(PLAYER, req).status());
        assertEquals(0, securities.depositCalls);
    }

    // ---------------------------------------------------------------- 旧版 SOURCE_DEBITED（第二节）

    @Test
    void oldSourceDebitedIsManualReviewNeverAutoMoves() {
        BankTransferRequest req = withdraw(101);
        ledger.write(PLAYER, sourceDebitedRecord(req, 0, 300));
        bank.setBalance(0);
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.depositCalls);
        assertEquals(0, securities.depositCalls);
    }

    // ---------------------------------------------------------------- PREPARED 白名单（第四节）

    @Test
    void preparedInsufficientFundsReplaysCleanFailure() {
        bank.setBalance(5);
        BankTransferRequest req = deposit(10);
        BankTransferResult first = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.INSUFFICIENT_FUNDS, first.status());
        bank.setBalance(5000); // 即使余额已恢复，也不重执行
        int wd = bank.withdrawCalls;
        int sd = securities.depositCalls;
        BankTransferResult replay = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.INSUFFICIENT_FUNDS, replay.status());
        assertEquals(wd, bank.withdrawCalls);
        assertEquals(sd, securities.depositCalls);
    }

    @Test
    void rejectedSeededUnavailableReplaysCleanly() {
        BankTransferRequest req = deposit(10);
        ledger.write(PLAYER, rejectedStatusRecord(req, BankTransferStatus.UNAVAILABLE));
        int wd = bank.withdrawCalls;
        BankTransferResult replay = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.UNAVAILABLE, replay.status());
        assertTrue(replay.duplicate());
        assertEquals(wd, bank.withdrawCalls);
        assertEquals(BankTransferPhase.REJECTED, ledger.find(PLAYER, req.requestId()).phase());
    }

    // ---------------------------------------------------------------- REJECTED 预算（第五轮，第二节）

    @Test
    void threeHundredInsufficientRequestsNeverPermanentlyBlock() {
        for (int i = 0; i < 300; i++) {
            bank.setBalance(5); // 不足 10
            BankTransferResult r = service.transfer(PLAYER, depositWithId(10, "poor-" + i));
            assertEquals(BankTransferStatus.INSUFFICIENT_FUNDS, r.status());
        }
        // 详细记录有界；每个拒绝都有墓碑。
        assertTrue(ledger.recent(PLAYER).size() <= FakeLedger.MAX);
        assertEquals(300, ledger.tombstoneCount());
        // 第 1 个失败 requestId 重放仍 INSUFFICIENT_FUNDS，资金 0。
        int wd = bank.withdrawCalls;
        BankTransferResult replay = service.transfer(PLAYER, depositWithId(10, "poor-0"));
        assertEquals(BankTransferStatus.INSUFFICIENT_FUNDS, replay.status());
        assertEquals(wd, bank.withdrawCalls);
        // 第 1 个 requestId 改金额 → REQUEST_CONFLICT。
        assertEquals(BankTransferStatus.REQUEST_CONFLICT,
                service.transfer(PLAYER, depositWithId(20, "poor-0")).status());
        assertEquals(wd, bank.withdrawCalls);
        // 第 301 个资金充足的新请求正常受理。
        bank.setBalance(1_000_000);
        securities.setBalance(1000);
        assertTrue(service.transfer(PLAYER, depositWithId(1, "rich-later")).success());
    }

    @Test
    void partialOperationBridgeFailureIsRejected() {
        bank.setBalance(5000);
        bank.setWithdrawFailsMode(BankTransferStatus.PARTIAL_OPERATION);
        BankTransferRequest req = deposit(10);
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.PARTIAL_OPERATION, r.status());
        assertEquals(BankTransferPhase.REJECTED, ledger.find(PLAYER, req.requestId()).phase());
        assertEquals(0, securities.depositCalls);
        // 重放零资金调用。
        assertEquals(BankTransferStatus.PARTIAL_OPERATION, service.transfer(PLAYER, req).status());
        assertEquals(0, securities.depositCalls);
    }

    @Test
    void compensationFailedWithDebitIsNeverRejectedNorRecreditsSecurities() {
        // bridge.withdraw 返回 COMPENSATION_FAILED 且 actualCopper > 0：不得 REJECTED，
        // 不得继续向证券入账，进入 COMPENSATION_FAILED，重放资金 0。
        bank.setBalance(5000);
        bank.setWithdrawFailsMode(BankTransferStatus.COMPENSATION_FAILED);
        bank.setWithdrawFailsActualCopper(4);
        BankTransferRequest req = deposit(10);
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.COMPENSATION_FAILED, r.status());
        assertEquals(BankTransferPhase.COMPENSATION_FAILED, ledger.find(PLAYER, req.requestId()).phase());
        assertEquals(0, bank.withdrawCalls, "bridge 失败态不视为成功扣款");
        assertEquals(0, securities.depositCalls, "不得继续向证券入账");
        // 重放只读、资金 0。
        assertEquals(BankTransferStatus.COMPENSATION_FAILED, service.transfer(PLAYER, req).status());
        assertEquals(0, securities.depositCalls);
    }

    // ---------------------------------------------------------------- write-ahead 写失败（第五节）

    private BankTransferResult depositWithWriteFailAt(int index) {
        ledger.failWriteAt(index);
        bank.setBalance(5000);
        securities.setBalance(1000);
        return service.transfer(PLAYER, depositWithId(10, "wf-" + index));
    }

    @Test
    void sourceDebitedWriteFailureStopsBeforeSecuritiesCredit() {
        BankTransferResult r = depositWithWriteFailAt(1);
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, r.status());
        assertEquals(1, bank.withdrawCalls, "LC 已扣");
        assertEquals(0, securities.depositCalls, "不得向证券入账");
    }

    @Test
    void destinationCreditPendingWriteFailureStopsBeforeTargetCredit() {
        BankTransferResult r = depositWithWriteFailAt(2);
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, r.status());
        assertEquals(1, bank.withdrawCalls);
        assertEquals(0, securities.depositCalls);
    }

    @Test
    void destinationCreditedWriteFailureDoesNotClaimSuccess() {
        BankTransferResult r = depositWithWriteFailAt(3);
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, r.status());
        assertFalse(r.success());
        assertEquals(1, securities.depositCalls, "目标已入账但不宣称已可靠完成");
    }

    @Test
    void completedWriteFailureDoesNotClaimSuccess() {
        BankTransferResult r = depositWithWriteFailAt(4);
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, r.status());
        assertFalse(r.success());
        assertEquals(1, bank.withdrawCalls);
        assertEquals(1, securities.depositCalls);
    }

    @Test
    void compensationPendingWriteFailureStopsBeforeBankCompensation() {
        bank.setBalance(5000);
        securities.setBalance(1000);
        securities.setCreditFails(true); // 证券入账失败 → 进入补偿
        ledger.failWriteAt(3); // COMPENSATION_PENDING 写失败
        BankTransferResult r = service.transfer(PLAYER, deposit(10));
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, r.status());
        assertEquals(1, bank.withdrawCalls, "LC 已扣");
        assertEquals(0, bank.depositCalls, "LC 补偿不得发生（COMPENSATION_PENDING 意图未落盘）");
        // 证券入账失败尝试未计入成功数（creditFails 提前返回）；重点是不发生补偿资金。
        assertFalse(r.success());
    }

    @Test
    void compensatedWriteFailureDoesNotClaimReliableCompletion() {
        bank.setBalance(5000);
        securities.setBalance(1000);
        securities.setCreditFails(true);
        ledger.failWriteAt(4); // COMPENSATED 写失败
        BankTransferResult r = service.transfer(PLAYER, deposit(10));
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, r.status());
        assertFalse(r.success());
        assertEquals(1, bank.depositCalls, "LC 补偿已发生");
    }

    // ---------------------------------------------------------------- WAL 接缝（第三节）

    private BankTransferService serviceWith(FakeWal wal) {
        return new BankTransferService(bank, securities, ledger, wal, MAX_COPPER, MAX_CENTS, RUNTIME_EPOCH);
    }

    @Test
    void walForceFailureFailsClosedWithZeroMoney() {
        FakeWal wal = new FakeWal();
        wal.failWalFrom(0); // 第一次（PREPARED）就 force 失败
        BankTransferResult r = serviceWith(wal).transfer(PLAYER, deposit(10));
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, r.status());
        assertEquals(0, bank.withdrawCalls, "WAL 未落盘，不得调用 LC");
        assertEquals(0, securities.depositCalls);
    }

    @Test
    void walForceFailureAtPendingStopsBeforeTargetCredit() {
        FakeWal wal = new FakeWal();
        wal.failWalFrom(2); // PREPARED(0) + SOURCE_DEBITED(1) 成功；PENDING(2) force 失败
        bank.setBalance(5000);
        securities.setBalance(1000);
        BankTransferResult r = serviceWith(wal).transfer(PLAYER, deposit(10));
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, r.status());
        assertEquals(1, bank.withdrawCalls, "LC 已扣（此前的意图已落盘）");
        assertEquals(0, securities.depositCalls, "目标入账意图未落盘，不得动证券");
    }

    @Test
    void preparedIllegalComboStillFailsClosed() {
        BankTransferRequest req = deposit(10);
        ledger.write(PLAYER, preparedStatusRecord(req, BankTransferStatus.SUCCESS));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, service.transfer(PLAYER, req).status());
        ledger.write(PLAYER, preparedStatusRecord(req, BankTransferStatus.COMPENSATION_FAILED));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, service.transfer(PLAYER, req).status());
        assertEquals(0, bank.withdrawCalls);
    }

    // ---------------------------------------------------------------- 墓碑 / 256 条防重（第三节）

    @Test
    void afterManyCompletedTransfersFirstRequestIdStillBlockedWithZeroCalls() {
        for (int i = 0; i < FakeLedger.MAX + 50; i++) {
            bank.setBalance(1_000_000);
            securities.setBalance(1000);
            assertTrue(service.transfer(PLAYER, depositWithId(1, "dep-" + i)).success());
        }
        assertNotNull(ledger.tombstoneFind("dep-0"));
        int wd = bank.withdrawCalls;
        int sd = securities.depositCalls;
        BankTransferResult replay = service.transfer(PLAYER, depositWithId(1, "dep-0"));
        assertEquals(BankTransferStatus.SUCCESS, replay.status());
        assertTrue(replay.duplicate());
        assertEquals(wd, bank.withdrawCalls, "墓碑重放零资金调用");
        assertEquals(sd, securities.depositCalls);
    }

    @Test
    void firstRequestIdDifferentAmountStillConflictsAfterEviction() {
        for (int i = 0; i < FakeLedger.MAX + 10; i++) {
            bank.setBalance(1_000_000);
            securities.setBalance(1000);
            assertTrue(service.transfer(PLAYER, depositWithId(1, "dep-" + i)).success());
        }
        int wd = bank.withdrawCalls;
        int sd = securities.depositCalls;
        BankTransferResult conflict = service.transfer(PLAYER, depositWithId(2, "dep-0"));
        assertEquals(BankTransferStatus.REQUEST_CONFLICT, conflict.status());
        assertEquals(wd, bank.withdrawCalls);
        assertEquals(sd, securities.depositCalls);
    }

    // ---------------------------------------------------------------- 冲突 / 其它

    @Test
    void sameRequestIdDifferentDirectionConflicts() {
        bank.setBalance(50_000);
        securities.setBalance(50_000);
        service.transfer(PLAYER, depositWithId(10, "same"));
        assertEquals(BankTransferStatus.REQUEST_CONFLICT,
                service.transfer(PLAYER, withdrawWithId(100, "same")).status());
    }

    @Test
    void manualReviewSeedReplayMovesNoMoney() {
        BankTransferRequest req = deposit(10);
        ledger.write(PLAYER, terminalRecord(req, BankTransferPhase.MANUAL_REVIEW, BankTransferStatus.MANUAL_REVIEW));
        bank.setBalance(5000);
        BankTransferResult r = service.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertTrue(r.duplicate());
        assertEquals(0, bank.withdrawCalls);
        assertEquals(0, securities.depositCalls);
    }

    @Test
    void completedReplayDoesNotMoveMoneyAgain() {
        bank.setBalance(5000);
        securities.setBalance(1000);
        BankTransferRequest req = deposit(10);
        assertTrue(service.transfer(PLAYER, req).success());
        BankTransferResult replay = service.transfer(PLAYER, req);
        assertTrue(replay.success());
        assertTrue(replay.duplicate());
        assertEquals(1, bank.withdrawCalls);
        assertEquals(1, securities.depositCalls);
    }

    // ---------------------------------------------------------------- helpers

    private static BankTransferRequest deposit(long copper) {
        return new BankTransferRequest(BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                copper, 0, "dep-" + copper);
    }

    private static BankTransferRequest depositWithId(long copper, String id) {
        return new BankTransferRequest(BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES, copper, 0, id);
    }

    private static BankTransferRequest withdraw(long requestedCents) {
        return new BankTransferRequest(BankTransferRequest.Direction.WITHDRAW_TO_BANK,
                0, requestedCents, "wd-" + requestedCents);
    }

    private static BankTransferRequest withdrawWithId(long requestedCents, String id) {
        return new BankTransferRequest(BankTransferRequest.Direction.WITHDRAW_TO_BANK, 0, requestedCents, id);
    }

    private static Derived derive(BankTransferRequest req) {
        if (req.isDepositToSecurities()) {
            long cents = ExchangeRates.copperToSecuritiesCents(req.requestedCopper());
            return new Derived(cents, cents, req.requestedCopper());
        }
        long copper = ExchangeRates.securitiesCentsToCopperCeil(req.requestedSecuritiesCents());
        long debit = ExchangeRates.copperToSecuritiesCents(copper);
        return new Derived(req.requestedSecuritiesCents(), debit, copper);
    }

    private record Derived(long requestedSecuritiesCents, long actualDebitCents, long copper) {
    }

    private static BankTransferRecord baseRecord(BankTransferRequest req, BankTransferPhase phase,
                                                 BankTransferStatus status, long bankCopper, long secCents) {
        Derived d = derive(req);
        return new BankTransferRecord(req.requestId(), req.direction(), phase, status, "",
                req.isDepositToSecurities() ? req.requestedCopper() : 0,
                d.requestedSecuritiesCents(), d.actualDebitCents(), d.copper(),
                "sm:bd:o", "sm:bc:o", "sm:sd:o", "sm:sc:o", "sm:rb:o", bankCopper, secCents,
                PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, RUNTIME_EPOCH);
    }

    private static BankTransferRecord destinationCreditPendingRecord(BankTransferRequest req,
                                                                     BankTransferRequest.Direction unused,
                                                                     long bankCopper, long secCents) {
        return baseRecord(req, BankTransferPhase.DESTINATION_CREDIT_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, bankCopper, secCents);
    }

    private static BankTransferRecord compensationPendingRecord(BankTransferRequest req,
                                                                BankTransferRequest.Direction unused,
                                                                long bankCopper, long secCents) {
        return baseRecord(req, BankTransferPhase.COMPENSATION_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, bankCopper, secCents);
    }

    private static BankTransferRecord sourceDebitedRecord(BankTransferRequest req, long bankCopper, long secCents) {
        return baseRecord(req, BankTransferPhase.SOURCE_DEBITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, bankCopper, secCents);
    }

    private static BankTransferRecord preparedStatusRecord(BankTransferRequest req, BankTransferStatus status) {
        return baseRecord(req, BankTransferPhase.PREPARED, status, 0, 0);
    }

    private static BankTransferRecord rejectedStatusRecord(BankTransferRequest req, BankTransferStatus status) {
        return baseRecord(req, BankTransferPhase.REJECTED, status, 0, 0);
    }

    private static BankTransferRecord terminalRecord(BankTransferRequest req,
                                                     BankTransferPhase phase, BankTransferStatus status) {
        return baseRecord(req, phase, status, 0, 0);
    }

    private static BankTransferRecord copyWithVersion(BankTransferRecord r, int version) {
        return new BankTransferRecord(r.requestId(), r.direction(), r.phase(), r.status(), r.message(),
                r.requestedCopper(), r.requestedSecuritiesCents(), r.actualDebitCents(), r.copperAmount(),
                r.opBankDebit(), r.opBankCredit(), r.opSecuritiesDebit(), r.opSecuritiesCredit(),
                r.opRollback(), r.bankBalanceCopper(), r.securitiesBalanceCents(), r.providerId(),
                r.operationIdVersion(), version, r.runtimeEpoch());
    }

    private static BankTransferRecord copyWithOpRollback(BankTransferRecord r, String opRollback) {
        return new BankTransferRecord(r.requestId(), r.direction(), r.phase(), r.status(), r.message(),
                r.requestedCopper(), r.requestedSecuritiesCents(), r.actualDebitCents(), r.copperAmount(),
                r.opBankDebit(), r.opBankCredit(), r.opSecuritiesDebit(), r.opSecuritiesCredit(),
                opRollback, r.bankBalanceCopper(), r.securitiesBalanceCents(), r.providerId(),
                r.operationIdVersion(), r.stateMachineVersion(), r.runtimeEpoch());
    }

    // ---------------------------------------------------------------- 旧版迁移全流程（第三节 / 第六节）

    /** 真实 AccountData 作账本的适配（用于迁移/墓碑淘汰集成测试）。 */
    static final class AccountDataLedger implements BankTransferLedger {
        final com.tanrunn.stockmarket.server.market.AccountData data;

        AccountDataLedger(com.tanrunn.stockmarket.server.market.AccountData data) {
            this.data = data;
        }

        @Override public boolean write(UUID playerId, BankTransferRecord record) {
            return data.upsertTransfer(record);
        }

        @Override public BankTransferRecord find(UUID playerId, String requestId) {
            return data.findByRequestId(requestId);
        }

        @Override public List<BankTransferRecord> recent(UUID playerId) {
            return new ArrayList<>(data.transfers);
        }
    }

    @Test
    void legacyCompletedTombstoneBackfilledReplayIsSafeAfterEvictionAndRestart() {
        // 旧版存档：详细列表只有一条 COMPLETED 入金（无墓碑）。
        com.tanrunn.stockmarket.server.market.AccountData oldData =
                new com.tanrunn.stockmarket.server.market.AccountData();
        BankTransferRecord legacy = baseRecord(depositWithId(10, "legacy-0"),
                BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 4990, 1000);
        oldData.transfers.add(legacy);
        // 模拟跨重启加载：deserialize 为安全终态补建墓碑。
        com.tanrunn.stockmarket.server.market.AccountData restored =
                new com.tanrunn.stockmarket.server.market.AccountData();
        restored.deserializeNBT(null, oldData.serializeNBT(null));
        assertEquals(1, restored.transferTombstones.size(), "重启后自动补建墓碑");
        AccountDataLedger ledger2 = new AccountDataLedger(restored);
        bank.setBalance(1_000_000);
        securities.setBalance(1000);
        BankTransferService svc =
                new BankTransferService(bank, securities, ledger2, MAX_COPPER, MAX_CENTS, RUNTIME_EPOCH);
        // 写入 256+ 新安全终态，旧详细行被淘汰。
        for (int i = 0; i < FakeLedger.MAX + 20; i++) {
            assertTrue(svc.transfer(PLAYER, depositWithId(1, "new-" + i)).success());
        }
        assertTrue(restored.transfers.stream().noneMatch(t -> t.requestId().equals("legacy-0")));
        // 旧 requestId、相同金额重放：duplicate、零资金调用。
        int wd = bank.withdrawCalls;
        int sd = securities.depositCalls;
        BankTransferResult r = svc.transfer(PLAYER, depositWithId(10, "legacy-0"));
        assertTrue(r.success());
        assertTrue(r.duplicate());
        assertEquals(wd, bank.withdrawCalls);
        assertEquals(sd, securities.depositCalls);
        // 旧 requestId、不同金额重放：REQUEST_CONFLICT、零资金调用。
        BankTransferResult c = svc.transfer(PLAYER, depositWithId(20, "legacy-0"));
        assertEquals(BankTransferStatus.REQUEST_CONFLICT, c.status());
        assertEquals(wd, bank.withdrawCalls);
        assertEquals(sd, securities.depositCalls);
    }

    // ---------------------------------------------------------------- fakes

    /** 幂等、可注入失败、可模拟 LRU 淘汰的 fake 银行桥（币种 = 铜币）。 */
    static final class FakeBank implements CurrencyBridge {
        private long balance;
        private boolean available = true;
        private boolean depositFails;
        private BankTransferStatus withdrawFailMode;
        private long withdrawFailActualCopper;
        private int capacity; // 0 = 不限；>0 模拟 LC 内存账本 LRU 容量
        private int withdrawCalls;
        private int depositCalls;
        private final List<String> allCredits = new ArrayList<>();
        private final Map<String, Long> debits = new LinkedHashMap<>();
        private final Map<String, Long> credits = new LinkedHashMap<>();

        FakeBank setBalance(long balance) { this.balance = balance; return this; }
        FakeBank setCapacity(int capacity) { this.capacity = capacity; return this; }
        void setWithdrawFailsMode(BankTransferStatus mode) { this.withdrawFailMode = mode; }
        void setWithdrawFailsActualCopper(long copper) { this.withdrawFailActualCopper = copper; }
        void setAvailable(boolean available) { this.available = available; }
        void setDepositFails(boolean fails) { this.depositFails = fails; }
        void seedDebit(String opId, long copper) { debits.put(opId, copper); }
        void seedCredit(String opId, long copper) { credits.put(opId, copper); balance += copper; }
        int withdrawCalls() { return withdrawCalls; }
        int depositCalls() { return depositCalls; }
        long balanceCopper() { return balance; }
        List<String> allCredits() { return allCredits; }

        @Override public String id() { return "server_menu:lc_bank_main"; }
        @Override public String displayName() { return "LC 银行账户"; }
        @Override public boolean isAvailable() { return available; }
        @Override public long balanceCopper(UUID playerId) { return balance; }

        private void evictIfNeeded(Map<String, Long> map) {
            if (capacity <= 0) return;
            while (map.size() > capacity) {
                map.remove(map.keySet().iterator().next());
            }
        }

        @Override
        public BridgeResult withdraw(UUID playerId, long copper, String source, String reason, String requestId) {
            if (!available) return BridgeResult.fail(BridgeStatusCode.UNAVAILABLE);
            if (withdrawFailMode != null) {
                long actual = withdrawFailActualCopper;
                String msg = "simulated withdraw fail " + withdrawFailMode;
                BridgeStatusCode code = withdrawFailMode == BankTransferStatus.COMPENSATION_FAILED
                        ? BridgeStatusCode.COMPENSATION_FAILED
                        : BridgeStatusCode.PARTIAL_OPERATION;
                return new BridgeResult(false, actual, code, msg);
            }
            Long seen = debits.get(requestId);
            if (seen != null) {
                if (seen == copper) return BridgeResult.ok(copper);
                return BridgeResult.fail(BridgeStatusCode.REQUEST_CONFLICT);
            }
            if (balance < copper) return BridgeResult.fail(BridgeStatusCode.INSUFFICIENT_FUNDS);
            debits.put(requestId, copper);
            evictIfNeeded(debits);
            balance -= copper;
            withdrawCalls++;
            return BridgeResult.ok(copper);
        }

        @Override
        public BridgeResult deposit(UUID playerId, long copper, String source, String reason, String requestId) {
            if (!available) return BridgeResult.fail(BridgeStatusCode.UNAVAILABLE);
            Long seen = credits.get(requestId);
            if (seen != null) {
                if (seen == copper) return BridgeResult.ok(copper);
                return BridgeResult.fail(BridgeStatusCode.REQUEST_CONFLICT);
            }
            if (depositFails) return BridgeResult.fail(BridgeStatusCode.PROVIDER_ERROR);
            credits.put(requestId, copper);
            evictIfNeeded(credits);
            balance += copper;
            depositCalls++;
            allCredits.add(requestId);
            return BridgeResult.ok(copper);
        }
    }

    /** 持久去重（模拟持久证券流水）、可注入失败的 fake 证券账户。 */
    static final class FakeSecurities implements BankTransferService.Securities {
        private long balance;
        private boolean available = true;
        private boolean creditFails;
        private int depositCalls;
        private int withdrawCalls;
        private final List<String> allCredits = new ArrayList<>();
        private final Map<String, Long> credits = new LinkedHashMap<>(); // opId → cents（持久去重）

        void setBalance(long balance) { this.balance = balance; }
        void setAvailable(boolean available) { this.available = available; }
        void setCreditFails(boolean fails) { this.creditFails = fails; }
        void seedCredit(String opId, long cents) { credits.put(opId, cents); }
        int depositCalls() { return depositCalls; }
        int withdrawCalls() { return withdrawCalls; }
        List<String> allCredits() { return allCredits; }
        long balanceCents() { return balance; }

        @Override public boolean isAvailable(UUID playerId) { return available; }
        @Override public long balanceCents(UUID playerId) { return balance; }

        @Override
        public BankTransferService.TransferOutcome deposit(UUID playerId, long cents,
                                                           String source, String reason, String requestId) {
            Long seen = credits.get(requestId);
            if (seen != null) {
                if (seen == cents) {
                    return new BankTransferService.TransferOutcome(true, balance, "已处理");
                }
                return new BankTransferService.TransferOutcome(false, balance, "冲突");
            }
            if (creditFails) {
                return new BankTransferService.TransferOutcome(false, balance, "入账失败");
            }
            credits.put(requestId, cents);
            balance += cents;
            depositCalls++;
            allCredits.add(requestId);
            return new BankTransferService.TransferOutcome(true, balance, "");
        }

        @Override
        public BankTransferService.TransferOutcome withdraw(UUID playerId, long cents,
                                                            String source, String reason, String requestId) {
            if (balance < cents) {
                return new BankTransferService.TransferOutcome(false, balance, "余额不足");
            }
            balance -= cents;
            withdrawCalls++;
            return new BankTransferService.TransferOutcome(true, balance, "");
        }
    }

    /** 可控 force 失败的 fake WAL 接缝。 */
    static final class FakeWal implements Wal {
        private int calls;
        private int failFrom = -1;

        void failWalFrom(int absoluteZeroBasedWriteCall) {
            this.failFrom = absoluteZeroBasedWriteCall;
        }

        @Override
        public boolean writeIntent(UUID playerId, BankTransferRecord record) {
            if (failFrom == calls) {
                return false;
            }
            calls++;
            return true;
        }
    }

    /** 内存账本：详细 transfers + 持久墓碑 + 容量（安全终态才可淘汰）。 */
    static final class FakeLedger implements BankTransferLedger {
        static final int MAX = 256;
        private final List<BankTransferRecord> transfers = new ArrayList<>();
        private final Map<String, BankTransferRecord> tombstones = new LinkedHashMap<>();
        private final List<BankTransferPhase> history = new ArrayList<>();
        private int writeCount;
        private int failAt = -1;

        void failWriteAt(int zeroBasedIndex) {
            this.failAt = zeroBasedIndex;
        }

        @Override
        public boolean write(UUID playerId, BankTransferRecord record) {
            if (record == null || record.requestId() == null || record.requestId().isBlank()) {
                return false;
            }
            if (failAt == writeCount) {
                writeCount++;
                return false;
            }
            writeCount++;
            history.add(record.phase());
            for (int i = 0; i < transfers.size(); i++) {
                if (record.requestId().equals(transfers.get(i).requestId())) {
                    transfers.set(i, record);
                    if (record.isSafeTerminal()) tombstones.put(record.requestId(), record);
                    return true;
                }
            }
            while (transfers.size() >= MAX) {
                BankTransferRecord last = transfers.get(transfers.size() - 1);
                if (last != null && last.isSafeTerminal()) {
                    transfers.remove(transfers.size() - 1);
                    continue;
                }
                return false;
            }
            transfers.add(0, record);
            if (record.isSafeTerminal()) tombstones.put(record.requestId(), record);
            return true;
        }

        @Override
        public BankTransferRecord find(UUID playerId, String requestId) {
            for (BankTransferRecord transfer : transfers) {
                if (requestId.equals(transfer.requestId())) return transfer;
            }
            return tombstones.get(requestId);
        }

        @Override
        public List<BankTransferRecord> recent(UUID playerId) {
            return new ArrayList<>(transfers);
        }

        boolean historyHasPhase(BankTransferPhase phase) {
            return history.contains(phase);
        }

        int historyIndexOf(BankTransferPhase phase) {
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i) == phase) return i;
            }
            return -1;
        }

        BankTransferRecord tombstoneFind(String requestId) {
            return tombstones.get(requestId);
        }

        int tombstoneCount() {
            return tombstones.size();
        }
    }
}
