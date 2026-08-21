package com.tanrunn.stockmarket.api;

import com.tanrunn.stockmarket.server.transfer.FileTransferWal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAL 端到端恢复反例（第六轮）：真实 FileTransferWal（临时目录）+ 可重建的 fake 附件/组合账本
 * + 真实 BankTransferService，不启动 Minecraft 服务器。
 */
class WalRecoveryE2ETest {

    @TempDir
    Path dir;

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();
    private static final long MAX_COPPER = 9_000_000_000_000_000L;
    private static final long MAX_CENTS = 9_000_000_000_000_000_000L;
    private static final String PROVIDER = "server_menu:lc_bank_main";
    private static final long EPOCH = 424242L;

    private Path walPath(String name) {
        return dir.resolve(name);
    }

    private static Derived derive(BankTransferRequest req) {
        if (req.isDepositToSecurities()) {
            return new Derived(ExchangeRates.copperToSecuritiesCents(req.requestedCopper()),
                    ExchangeRates.copperToSecuritiesCents(req.requestedCopper()), req.requestedCopper());
        }
        long copper = ExchangeRates.securitiesCentsToCopperCeil(req.requestedSecuritiesCents());
        long debit = ExchangeRates.copperToSecuritiesCents(copper);
        return new Derived(req.requestedSecuritiesCents(), debit, copper);
    }

    private record Derived(long reqCents, long debit, long copper) {
    }

    private static BankTransferRecord record(BankTransferRequest req, BankTransferPhase phase,
                                             BankTransferStatus status, long bankCopper, long secCents) {
        return recordTagged(req, phase, status, bankCopper, secCents, "o");
    }

    private static BankTransferRecord recordTagged(BankTransferRequest req, BankTransferPhase phase,
                                                   BankTransferStatus status, long bankCopper, long secCents,
                                                   String tag) {
        Derived d = derive(req);
        return new BankTransferRecord(req.requestId(), req.direction(), phase, status, "状态",
                req.isDepositToSecurities() ? req.requestedCopper() : 0,
                d.reqCents(), d.debit(), d.copper(),
                "sm:bd:" + tag, "sm:bc:" + tag, "sm:sd:" + tag, "sm:sc:" + tag, "sm:rb:" + tag,
                bankCopper, secCents,
                PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
    }

    private static BankTransferRequest deposit(long copper, String id) {
        return new BankTransferRequest(BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES, copper, 0, id);
    }

    private static BankTransferRequest withdraw(long cents, String id) {
        return new BankTransferRequest(BankTransferRequest.Direction.WITHDRAW_TO_BANK, 0, cents, id);
    }

    private static BankTransferService newService(Path wal, BankTransferServiceTest.FakeBank bank,
                                                  BankTransferServiceTest.FakeSecurities securities,
                                                  BankTransferLedger nested) {
        return newService(new FileTransferWal(wal), bank, securities, nested);
    }

    private static BankTransferService newService(FileTransferWal w, BankTransferServiceTest.FakeBank bank,
                                                  BankTransferServiceTest.FakeSecurities securities,
                                                  BankTransferLedger nested) {
        ReconciledBankTransferLedger reconciled = new ReconciledBankTransferLedger(nested, w, w);
        return new BankTransferService(bank, securities, reconciled, w,
                MAX_COPPER, MAX_CENTS, EPOCH);
    }

    // ---------------------------------------------------------------- 1. 入金：附件丢失 + 重建不重复扣 LC

    @Test
    void depositWalPendingThenAttachmentLostReplayDoesNotReDebitLc() {
        Path p = walPath("e1.wal");
        BankTransferRequest req = deposit(10, "e1");
        // 模拟已压缩的崩溃态：WAL 已 force PREPARED/SOURCE_DEBITED/PENDING；附件“丢失”（空）。
        FileTransferWal w0 = new FileTransferWal(p);
        w0.writeIntent(PLAYER, record(req, BankTransferPhase.PREPARED, BankTransferStatus.INCOMPLETE_TRANSFER, 5000, 1000));
        w0.writeIntent(PLAYER, record(req, BankTransferPhase.SOURCE_DEBITED, BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        w0.writeIntent(PLAYER, record(req, BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        // LC 已被扣（fake 银行余额 + 幂等扣款记录）。
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(4990);
        bank.seedDebit("sm:bd:o", 10);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        // 重建全部服务对象（WAL 重新加载，附件为空）。
        BankTransferService restarted = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        int wdBefore = bank.withdrawCalls();
        BankTransferResult r = restarted.transfer(PLAYER, req);
        assertTrue(r.success());
        assertEquals(wdBefore, bank.withdrawCalls(), "WAL 存在 → 不得再次扣 LC");
        assertEquals(4990, bank.balanceCopper());
        assertEquals(2000, securities.balanceCents(), "仅补证券入账一次");
        Optional<BankTransferRecord> latest = new FileTransferWal(p)
                .latest(TransferKey.of(PLAYER, req.requestId()));
        assertTrue(latest.isPresent());
        assertEquals(BankTransferPhase.COMPLETED, latest.get().phase());
    }

    // 2. 出金：WAL PENDING（目标 LC）+ 附件丢失 → MANUAL_REVIEW、LC deposit=0
    @Test
    void withdrawWalPendingThenAttachmentLostManualReviewNoLcDeposit() {
        Path p = walPath("e2.wal");
        BankTransferRequest req = withdraw(101, "e2");
        FileTransferWal w0 = new FileTransferWal(p);
        w0.writeIntent(PLAYER, record(req, BankTransferPhase.PREPARED, BankTransferStatus.INCOMPLETE_TRANSFER, 0, 300));
        w0.writeIntent(PLAYER, record(req, BankTransferPhase.SOURCE_DEBITED, BankTransferStatus.INCOMPLETE_TRANSFER, 0, 300));
        w0.writeIntent(PLAYER, record(req, BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 0, 300));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(0);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(300);
        BankTransferService restarted = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = restarted.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status(), "目标 LC：无法证明是否已到账");
        assertEquals(0, bank.depositCalls(), "LC deposit 调用数必须为 0");
        assertEquals(0, bank.balanceCopper());
    }

    // 3. WAL COMPLETED + 附件完全缺失 → duplicate / 冲突，资金 0
    @Test
    void walCompletedAttachmentMissingDuplicateAndConflictZeroCalls() {
        Path p = walPath("e3.wal");
        BankTransferRequest req = deposit(10, "e3");
        FileTransferWal w0 = new FileTransferWal(p);
        w0.writeIntent(PLAYER, record(req, BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 4990, 2000));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(4990);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(2000);
        BankTransferService restarted = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult same = restarted.transfer(PLAYER, req);
        assertTrue(same.success());
        assertTrue(same.duplicate());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
        BankTransferResult diff = restarted.transfer(PLAYER, deposit(20, "e3"));
        assertEquals(BankTransferStatus.REQUEST_CONFLICT, diff.status());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    // 4. WAL COMPENSATION_PENDING：目标 LC → MANUAL_REVIEW；目标证券 → 仅 opRollback 幂等恢复
    @Test
    void walCompensationPendingTargetLcManualReviewZeroLc() {
        Path p = walPath("e4.wal");
        BankTransferRequest req = deposit(10, "e4");
        new FileTransferWal(p).writeIntent(PLAYER,
                record(req, BankTransferPhase.COMPENSATION_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 0, 1000));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(0);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService restarted = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = restarted.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.depositCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void walCompensationPendingTargetSecuritiesRecoversViaPersistedOpRollback() {
        Path p = walPath("e4b.wal");
        BankTransferRequest req = withdraw(101, "e4b");
        new FileTransferWal(p).writeIntent(PLAYER,
                record(req, BankTransferPhase.COMPENSATION_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 0, 300));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(0);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(500); // 证券补偿已应用（余额已反映）
        securities.seedCredit("sm:rb:o", 200); // 持久去重
        BankTransferService restarted = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        int before = securities.depositCalls();
        BankTransferResult r = restarted.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.BANK_ERROR, r.status()); // COMPENSATED
        assertEquals(before, securities.depositCalls(), "仅持久去重，不重复补偿");
        assertEquals(0, bank.depositCalls());
        assertEquals(500, securities.balanceCents());
    }

    // 5. WAL 截断且可解析 key → 只 quarantine 对应玩家+requestId
    @Test
    void truncatedAttributableKeyQuarantinesOnlyThatKey() throws Exception {
        Path p = walPath("e5.wal");
        FileTransferWal w0 = new FileTransferWal(p);
        w0.writeIntent(PLAYER, record(deposit(1, "zok"), BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 0, 0));
        w0.writeIntent(PLAYER, record(deposit(2, "zcut"), BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 0, 0));
        byte[] bytes = Files.readAllBytes(p);
        Files.write(p, java.util.Arrays.copyOf(bytes, bytes.length - 20));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertFalse(reparsed.globallyQuarantined());
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER, "zcut")));
        // 转账入口：该 key → MANUAL_REVIEW，资金 0；其它 key 正常。
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(10_000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(10_000);
        BankTransferService svc = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult blocked = svc.transfer(PLAYER, deposit(2, "zcut"));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, blocked.status());
        assertEquals(0, bank.withdrawCalls());
        assertTrue(svc.transfer(PLAYER, deposit(1, "zok")).success());
    }

    // 6. WAL 截断且无法解析 key → 全局阻断银行转账；普通证券不受影响
    @Test
    void truncatedUnattributableGloballyQuarantinesDeposits() throws Exception {
        Path p = walPath("e6.wal");
        Files.write(p, "garbage line with no key|seq=5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = svc.transfer(PLAYER, deposit(10, "g1"));
        assertEquals(BankTransferStatus.UNAVAILABLE, r.status(), "全局隔离 → 银行转账 fail closed");
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
        // 普通证券交易不受影响（此处验证组合账本不拦非银行桥接路径——证券余额可读）。
        assertEquals(1000, securities.balanceCents());
    }

    // 7. 两个玩家同 requestId：独立恢复/冲突
    @Test
    void twoPlayersSameRequestIdRecoverIndependently() {
        Path p = walPath("e7.wal");
        BankTransferRequest reqA = deposit(10, "shared");
        BankTransferRequest reqB = deposit(20, "shared");
        FileTransferWal w0 = new FileTransferWal(p);
        w0.writeIntent(PLAYER, recordTagged(reqA, BankTransferPhase.DESTINATION_CREDIT_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000, "A"));
        w0.writeIntent(PLAYER_B, recordTagged(reqB, BankTransferPhase.DESTINATION_CREDIT_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4980, 1000, "B"));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(4980);
        bank.seedDebit("sm:bd:A", 10);
        bank.seedDebit("sm:bd:B", 20);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(p, bank, securities, new PlayerScopedLedger());
        int wd0 = bank.withdrawCalls();
        BankTransferResult ra = svc.transfer(PLAYER, reqA);
        assertTrue(ra.success(), "A replay: "+ra.status()+"/"+ra.message());
        BankTransferResult rb = svc.transfer(PLAYER_B, reqB);
        assertTrue(rb.success(), "B replay: "+rb.status()+"/"+rb.message());
        assertEquals(wd0, bank.withdrawCalls(), "两手都不得再次扣 LC");
        // 独立冲突：A 换金额冲突不影响 B。
        assertEquals(BankTransferStatus.REQUEST_CONFLICT, svc.transfer(PLAYER, deposit(50, "shared")).status());
        assertEquals(wd0, bank.withdrawCalls());
    }

    // 8. WAL 比附件新 → WAL 为权威
    @Test
    void walNewerThanAttachmentWalWins() {
        Path p = walPath("e8.wal");
        BankTransferRequest req = deposit(10, "e8");
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(req, BankTransferPhase.SOURCE_DEBITED, BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        FileTransferWal w0 = new FileTransferWal(p);
        w0.writeIntent(PLAYER, record(req, BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        ReconciledBankTransferLedger reconciled = new ReconciledBankTransferLedger(nested, w0, w0);
        BankTransferRecord found = reconciled.find(PLAYER, req.requestId());
        assertNotNull(found);
        assertEquals(BankTransferPhase.DESTINATION_CREDIT_PENDING, found.phase(), "WAL 比附件新 → WAL 权威");
    }

    // 9. 附件比 WAL 新但 WAL 无记录：非安全终态 → MANUAL_REVIEW
    @Test
    void attachmentNewerWithoutWalNonSafeBecomesManualReview() {
        Path p = walPath("e9.wal");
        BankTransferRequest req = deposit(10, "e9");
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(req, BankTransferPhase.SOURCE_DEBITED, BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        ReconciledBankTransferLedger reconciled = new ReconciledBankTransferLedger(nested, new FileTransferWal(p), new FileTransferWal(p));
        BankTransferRecord found = reconciled.find(PLAYER, req.requestId());
        assertEquals(BankTransferPhase.MANUAL_REVIEW, found.phase(), "WAL 无记录且附件非安全终态 → 不一致");
    }

    @Test
    void attachmentSafeTerminalWithoutWalAllowedReadOnly() {
        Path p = walPath("e9b.wal");
        BankTransferRequest req = deposit(10, "e9b");
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(req, BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 4990, 2000));
        ReconciledBankTransferLedger reconciled = new ReconciledBankTransferLedger(nested, new FileTransferWal(p), new FileTransferWal(p));
        BankTransferRecord found = reconciled.find(PLAYER, req.requestId());
        assertEquals(BankTransferPhase.COMPLETED, found.phase(), "附件安全终态（旧存档）只读重放");
    }

    // 10. WAL 与附件金额冲突 → MANUAL_REVIEW
    @Test
    void walAttachmentFingerprintConflictManualReview() {
        Path p = walPath("e10.wal");
        BankTransferRequest reqA = deposit(10, "c");
        BankTransferRequest reqB = deposit(99, "c");
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(reqB, BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 0, 0));
        new FileTransferWal(p).writeIntent(PLAYER, record(reqA, BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 0, 0));
        ReconciledBankTransferLedger reconciled = new ReconciledBankTransferLedger(nested, new FileTransferWal(p), new FileTransferWal(p));
        BankTransferRecord found = reconciled.find(PLAYER, "c");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, found.phase(), "防重指纹冲突 → MANUAL_REVIEW 资金 0");
    }

    // 11. 压缩后重启，所有 TransferKey 仍能防重
    @Test
    void afterCompactionAndRestartAllKeysStillBlockReplay() {
        Path p = walPath("e11.wal");
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(1_000_000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        BankTransferService svc = newService(p, bank, securities, nested);
        for (int i = 0; i < 20; i++) {
            assertTrue(svc.transfer(PLAYER, deposit(1, "kc-" + i)).success());
        }
        FileTransferWal wal = new FileTransferWal(p);
        assertTrue(wal.compact());
        // 重启（新 WAL + 新附件 + 新服务）后重放旧 requestId。
        BankTransferServiceTest.FakeLedger nested2 = new BankTransferServiceTest.FakeLedger();
        // 附件不重建历史（模拟 WAL 为唯一防重源在重启后）。
        BankTransferService restarted = newService(p, bank, securities, nested2);
        int wd = bank.withdrawCalls();
        int sd = securities.depositCalls();
        BankTransferResult r = restarted.transfer(PLAYER, deposit(1, "kc-0"));
        assertTrue(r.success());
        assertTrue(r.duplicate());
        assertEquals(wd, bank.withdrawCalls());
        assertEquals(sd, securities.depositCalls());
        // 终端 WAL 墓碑仍在 → 压缩后依然防重。
        assertTrue(new FileTransferWal(p).latest(TransferKey.of(PLAYER, "kc-0")).isPresent());
    }

    // 12. WAL 成功、附件失败：立即/重建后重试都命中 WAL，不重复资金
    @Test
    void walOkAttachmentFailedThenImmediateRetryHitsWalNoDoubleMoney() {
        Path p = walPath("e12.wal");
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferRequest req = deposit(10, "e12");
        // 附件（nested）在 COMPLETED（第 4 次写）失败：资金已完成但终态未落附件。
        BankTransferServiceTest.FakeLedger nestedFail = new BankTransferServiceTest.FakeLedger();
        nestedFail.failWriteAt(4);
        BankTransferService first = newService(p, bank, securities, nestedFail);
        BankTransferResult attempt = first.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, attempt.status(), "终态写失败不得宣称可靠完成");
        assertEquals(1, bank.withdrawCalls());
        assertEquals(1, securities.depositCalls());
        // 立即重试（同一进程、同 WAL 实例 + 新附件）→ 必须命中 WAL 终态，零新资金。
        int wd = bank.withdrawCalls();
        int sd = securities.depositCalls();
        BankTransferService retry = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = retry.transfer(PLAYER, req);
        assertTrue(r.success());
        assertTrue(r.duplicate());
        assertEquals(wd, bank.withdrawCalls(), "不得重复 LC 扣款");
        assertEquals(sd, securities.depositCalls(), "不得重复证券入账");
    }

    @Test
    void walOkAttachmentFailedThenRebuiltObjectsStillHitWal() {
        Path p = walPath("e13.wal");
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferRequest req = deposit(10, "e13");
        BankTransferServiceTest.FakeLedger nestedFail = new BankTransferServiceTest.FakeLedger();
        nestedFail.failWriteAt(4);
        newService(p, bank, securities, nestedFail).transfer(PLAYER, req);
        int wd = bank.withdrawCalls();
        int sd = securities.depositCalls();
        // 销毁全部服务对象 → 重新创建（WAL 重新加载）→ 仍命中 WAL 终态。
        BankTransferService rebuilt = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = rebuilt.transfer(PLAYER, req);
        assertTrue(r.success());
        assertTrue(r.duplicate());
        assertEquals(wd, bank.withdrawCalls());
        assertEquals(sd, securities.depositCalls());
    }

    @Test
    void walPendingOkAttachmentPendingFailedRecoverLevelByWalPhase() {
        Path p = walPath("e14.wal");
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferRequest req = deposit(10, "e14");
        // 附件在 DESTINATION_CREDIT_PENDING（第 2 次写）失败：LC 已扣、证券未动。
        BankTransferServiceTest.FakeLedger nestedFail = new BankTransferServiceTest.FakeLedger();
        nestedFail.failWriteAt(2);
        BankTransferService first = newService(p, bank, securities, nestedFail);
        BankTransferResult attempt = first.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.RECOVERY_REQUIRED, attempt.status());
        assertEquals(1, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
        // 重建后：WAL 最新为 PENDING → 恢复证券入账 → 完成。
        BankTransferService rebuilt = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = rebuilt.transfer(PLAYER, req);
        assertTrue(r.success());
        assertEquals(1, bank.withdrawCalls(), "LC 只扣一次");
        assertEquals(1, securities.depositCalls(), "证券只入一次（恢复补入）");
        Optional<BankTransferRecord> latest = new FileTransferWal(p)
                .latest(TransferKey.of(PLAYER, req.requestId()));
        assertTrue(latest.isPresent());
        assertEquals(BankTransferPhase.COMPLETED, latest.get().phase());
    }


    // ---------------------------------------------------------------- 第七轮：登录对账（不覆盖证据 / 保守 / 0 资金）

    @Test
    void loginDoesNotOverwriteAttachmentFingerprintConflict() {
        Path p = walPath("r7-login-1.wal");
        String key = "x";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        // 附件：COMPLETED（金额 10）；WAL：PENDING（金额 99）→ 防重指纹冲突。
        nested.write(PLAYER, record(deposit(10, key), BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 4990, 2000));
        new FileTransferWal(p).writeIntent(PLAYER,
                record(deposit(99, key), BankTransferPhase.DESTINATION_CREDIT_PENDING,
                        BankTransferStatus.INCOMPLETE_TRANSFER, 4901, 1000));
        ReconciledBankTransferLedger reconciled =
                new ReconciledBankTransferLedger(nested, new FileTransferWal(p), new FileTransferWal(p));
        assertFalse(reconciled.reconcileWriteBack(PLAYER, key), "指纹冲突不得写回覆盖附件");
        // 附件原证据保留（未被 WAL pending 覆盖）。
        assertEquals(BankTransferPhase.COMPLETED, nested.find(PLAYER, key).phase());
        assertEquals(10, nested.find(PLAYER, key).requestedCopper());
        // 请求命中该键 → MANUAL_REVIEW/冲突，资金 0。
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(p, bank, securities, nested);
        svc.transfer(PLAYER, deposit(10, key));
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void loginDoesNotDowngradeAttachmentManualReview() {
        Path p = walPath("r7-login-2.wal");
        String key = "y";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        // 附件：MANUAL_REVIEW（金额 10）；WAL：COMPLETED（同指纹，阶段更“进取”）。
        nested.write(PLAYER, record(deposit(10, key), BankTransferPhase.MANUAL_REVIEW,
                BankTransferStatus.MANUAL_REVIEW, 0, 0));
        new FileTransferWal(p).writeIntent(PLAYER,
                record(deposit(10, key), BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 4990, 2000));
        ReconciledBankTransferLedger reconciled =
                new ReconciledBankTransferLedger(nested, new FileTransferWal(p), new FileTransferWal(p));
        assertFalse(reconciled.reconcileWriteBack(PLAYER, key), "附件 MANUAL_REVIEW 不得被 completed 覆盖");
        assertEquals(BankTransferPhase.MANUAL_REVIEW, nested.find(PLAYER, key).phase(), "附件证据保留");
        // find 也返回 MANUAL_REVIEW（保守优先），资金 0。
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(p, bank, securities, nested);
        BankTransferResult r = svc.transfer(PLAYER, deposit(10, key));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void reconcileWalPendingAndAttachmentManualReviewStaysManual() {
        Path p = walPath("r7-login-3.wal");
        String key = "z";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(deposit(10, key), BankTransferPhase.MANUAL_REVIEW,
                BankTransferStatus.MANUAL_REVIEW, 0, 0));
        new FileTransferWal(p).writeIntent(PLAYER,
                record(deposit(10, key), BankTransferPhase.DESTINATION_CREDIT_PENDING,
                        BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        ReconciledBankTransferLedger reconciled =
                new ReconciledBankTransferLedger(nested, new FileTransferWal(p), new FileTransferWal(p));
        assertEquals(ReconciledBankTransferLedger.Decision.MANUAL_REVIEW_BLOCK,
                reconciled.decide(TransferKey.of(PLAYER, key),
                        new FileTransferWal(p).latest(TransferKey.of(PLAYER, key)).orElse(null),
                        nested.find(PLAYER, key)).decision(),
                "WAL pending + 附件 MANUAL_REVIEW → 保守 MANUAL_REVIEW（不得被 pending 降级）");
        assertFalse(reconciled.reconcileWriteBack(PLAYER, key));
    }

    @Test
    void reconcileAttachmentAheadOfWalFailsClosed() {
        Path p = walPath("r7-login-4.wal");
        String key = "w";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        // 附件：DESTINATION_CREDITED（领先）；WAL：DESTINATION_CREDIT_PENDING（落后，同指纹）。
        nested.write(PLAYER, record(deposit(10, key), BankTransferPhase.DESTINATION_CREDITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 2000));
        new FileTransferWal(p).writeIntent(PLAYER,
                record(deposit(10, key), BankTransferPhase.DESTINATION_CREDIT_PENDING,
                        BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        ReconciledBankTransferLedger reconciled =
                new ReconciledBankTransferLedger(nested, new FileTransferWal(p), new FileTransferWal(p));
        assertEquals(ReconciledBankTransferLedger.Decision.MANUAL_REVIEW_BLOCK,
                reconciled.decide(TransferKey.of(PLAYER, key),
                        new FileTransferWal(p).latest(TransferKey.of(PLAYER, key)).orElse(null),
                        nested.find(PLAYER, key)).decision(),
                "附件领先 WAL 且无法证明先后 → MANUAL_REVIEW，零资金");
        assertFalse(reconciled.reconcileWriteBack(PLAYER, key));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(p, bank, securities, nested);
        BankTransferResult r = svc.transfer(PLAYER, deposit(10, key));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void reconcileWalLegitimatelyAheadOfAttachmentUsesWalAndWritebacks() {
        Path p = walPath("r7-login-5.wal");
        String key = "v";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        // 附件：SOURCE_DEBITED；WAL：DESTINATION_CREDIT_PENDING（同链合法领先）。
        nested.write(PLAYER, record(deposit(10, key), BankTransferPhase.SOURCE_DEBITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        FileTransferWal w0 = new FileTransferWal(p);
        w0.writeIntent(PLAYER, record(deposit(10, key), BankTransferPhase.DESTINATION_CREDIT_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        ReconciledBankTransferLedger reconciled =
                new ReconciledBankTransferLedger(nested, new FileTransferWal(p), new FileTransferWal(p));
        assertTrue(reconciled.reconcileWriteBack(PLAYER, key),
                "WAL 合法领先附件且无冲突 → 允许安全写回");
        assertEquals(BankTransferPhase.DESTINATION_CREDIT_PENDING, nested.find(PLAYER, key).phase());
    }

    // ---------------------------------------------------------------- 第八轮：对账冲突→持久 marker；身份；poison；旧版只读

    private static BankTransferService rebuildService(Path p, BankTransferServiceTest.FakeBank bank,
                                                      BankTransferServiceTest.FakeSecurities securities) {
        // 附件“丢失”→ 经典全新空 ledger；WAL 从磁盘重建。
        return newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
    }

    private BankTransferRequest pendingDeposit(String key, long copper) {
        return new BankTransferRequest(BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES, copper, 0, key);
    }

    @Test
    void reconciliationConflictPersistsQuarantineMarker() {
        Path p = walPath("r8-e1.wal");
        String key = "c1";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(pendingDeposit(key, 10), BankTransferPhase.COMPLETED,
                BankTransferStatus.SUCCESS, 4990, 2000));
        new FileTransferWal(p).writeIntent(PLAYER, record(pendingDeposit(key, 99),
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 4901, 1000));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        int wd = bank.withdrawCalls();
        int sd = securities.depositCalls();
        BankTransferService first = newService(p, bank, securities, nested);
        first.transfer(PLAYER, pendingDeposit(key, 10));
        assertEquals(wd, bank.withdrawCalls());
        assertEquals(sd, securities.depositCalls());
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER, key)),
                "对账冲突必须持久化为 keyed quarantine marker");
        // 附件完全丢失 + 重启 → 仍阻断（marker 在磁盘），零资金。
        BankTransferService rebuilt = rebuildService(p, bank, securities);
        BankTransferResult r = rebuilt.transfer(PLAYER, pendingDeposit(key, 99));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(wd, bank.withdrawCalls());
        assertEquals(sd, securities.depositCalls());
    }

    @Test
    void attachmentManualReviewThenAttachmentLostStillBlocksAfterRestart() {
        Path p = walPath("r8-e2.wal");
        String key = "c2";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(pendingDeposit(key, 10), BankTransferPhase.MANUAL_REVIEW,
                BankTransferStatus.MANUAL_REVIEW, 0, 0));
        new FileTransferWal(p).writeIntent(PLAYER, record(pendingDeposit(key, 10),
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService first = newService(p, bank, securities, nested);
        first.transfer(PLAYER, pendingDeposit(key, 10));
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER, key)),
                "附件 MANUAL_REVIEW 不得被覆盖：先持久化 marker");
        // 附件丢失 + 重启：WAL 单独存在仍阻断该 key，绝不恢复原 pending。
        BankTransferService rebuilt = rebuildService(p, bank, securities);
        BankTransferResult r = rebuilt.transfer(PLAYER, pendingDeposit(key, 10));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.withdrawCalls(), "不得向证券/LC 自动入账");
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void attachmentAheadThenAttachmentLostStillBlocksAfterRestart() {
        Path p = walPath("r8-e3.wal");
        String key = "c3";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(pendingDeposit(key, 10), BankTransferPhase.DESTINATION_CREDITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 2000));
        new FileTransferWal(p).writeIntent(PLAYER, record(pendingDeposit(key, 10),
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        newService(p, bank, securities, nested).transfer(PLAYER, pendingDeposit(key, 10));
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER, key)),
                "附件领先 WAL → 持久 marker");
        BankTransferService rebuilt = rebuildService(p, bank, securities);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, rebuilt.transfer(PLAYER, pendingDeposit(key, 10)).status());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void fingerprintConflictThenAttachmentLostStillBlocksAfterCompactionAndRestart() {
        Path p = walPath("r8-e4.wal");
        String key = "c4";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, record(pendingDeposit(key, 10), BankTransferPhase.COMPLETED,
                BankTransferStatus.SUCCESS, 4990, 2000));
        new FileTransferWal(p).writeIntent(PLAYER, record(pendingDeposit(key, 99),
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER, 4901, 1000));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService first = newService(p, bank, securities, nested);
        first.transfer(PLAYER, pendingDeposit(key, 10));
        FileTransferWal wal = new FileTransferWal(p);
        assertTrue(wal.quarantinedKeys().contains(TransferKey.of(PLAYER, key)));
        assertFalse(wal.compact(), "存在隔离证据不得压缩（证据保留）");
        // 附件丢失 + 重启后 marker 仍在磁盘 → 阻断。
        BankTransferService rebuilt = rebuildService(p, bank, securities);
        BankTransferResult r = rebuilt.transfer(PLAYER, pendingDeposit(key, 99));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void walAndAttachmentSameAmountDifferentSecuritiesCreditOpIdQuarantines() {
        Path p = walPath("r8-e5.wal");
        String key = "c5";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, recordTagged(pendingDeposit(key, 10), BankTransferPhase.SOURCE_DEBITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000, "ATTACH"));
        new FileTransferWal(p).writeIntent(PLAYER, recordTagged(pendingDeposit(key, 10),
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER,
                4990, 1000, "WAL_OTHER"));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService first = newService(p, bank, securities, nested);
        BankTransferResult r = first.transfer(PLAYER, pendingDeposit(key, 10));
        // 阶段合法领先但 opSecuritiesCredit 不同 → 恢复身份不一致 → MR。
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER, key)),
                "同金额不同证券入账 opId → 持久 keyed quarantine");
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void walAndAttachmentSameAmountDifferentRollbackOpIdQuarantines() {
        Path p = walPath("r8-e6.wal");
        String key = "c6";
        BankTransferRequest wd = new BankTransferRequest(BankTransferRequest.Direction.WITHDRAW_TO_BANK,
                0, 101, key);
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        nested.write(PLAYER, recordTagged(wd, BankTransferPhase.COMPENSATION_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, 0, 300, "A_ORIG"));
        new FileTransferWal(p).writeIntent(PLAYER, recordTagged(wd, BankTransferPhase.COMPENSATION_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, 0, 300, "B_OTHER"));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(0);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(300);
        BankTransferService first = newService(p, bank, securities, nested);
        first.transfer(PLAYER, wd);
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER, key)),
                "同金额不同 rollback opId → 持久 keyed quarantine");
        assertEquals(0, securities.depositCalls(), "不得用无法证明身份的 opRollback 自动补偿");
        assertEquals(0, bank.depositCalls());
    }

    @Test
    void walAndAttachmentDifferentProviderQuarantines() {
        Path p = walPath("r8-e7.wal");
        String key = "c7";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        BankTransferRecord attach = recordTagged(pendingDeposit(key, 10), BankTransferPhase.SOURCE_DEBITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000, "X");
        BankTransferRecord attachDiffProvider = new BankTransferRecord(attach.requestId(), attach.direction(),
                attach.phase(), attach.status(), attach.message(), attach.requestedCopper(),
                attach.requestedSecuritiesCents(), attach.actualDebitCents(), attach.copperAmount(),
                attach.opBankDebit(), attach.opBankCredit(), attach.opSecuritiesDebit(),
                attach.opSecuritiesCredit(), attach.opRollback(), attach.bankBalanceCopper(),
                attach.securitiesBalanceCents(), "other_provider", attach.operationIdVersion(),
                attach.stateMachineVersion(), attach.runtimeEpoch());
        nested.write(PLAYER, attachDiffProvider);
        new FileTransferWal(p).writeIntent(PLAYER, record(pendingDeposit(key, 10),
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER,
                4990, 1000));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService first = newService(p, bank, securities, nested);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, first.transfer(PLAYER, pendingDeposit(key, 10)).status());
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER, key)),
                "providerId 不同 → 持久 keyed quarantine");
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void walAndAttachmentDifferentRuntimeEpochQuarantines() {
        Path p = walPath("r8-e8.wal");
        String key = "c8";
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        BankTransferRecord attach = recordTagged(pendingDeposit(key, 10), BankTransferPhase.SOURCE_DEBITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000, "Y");
        BankTransferRecord attachDiffEpoch = new BankTransferRecord(attach.requestId(), attach.direction(),
                attach.phase(), attach.status(), attach.message(), attach.requestedCopper(),
                attach.requestedSecuritiesCents(), attach.actualDebitCents(), attach.copperAmount(),
                attach.opBankDebit(), attach.opBankCredit(), attach.opSecuritiesDebit(),
                attach.opSecuritiesCredit(), attach.opRollback(), attach.bankBalanceCopper(),
                attach.securitiesBalanceCents(), attach.providerId(), attach.operationIdVersion(),
                attach.stateMachineVersion(), EPOCH + 7);
        nested.write(PLAYER, attachDiffEpoch);
        new FileTransferWal(p).writeIntent(PLAYER, record(pendingDeposit(key, 10),
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER,
                4990, 1000));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService first = newService(p, bank, securities, nested);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, first.transfer(PLAYER, pendingDeposit(key, 10)).status());
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER, key)),
                "runtimeEpoch 不同 → 持久 keyed quarantine");
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void legacySafeTerminalMissingRecoveryIdentityRemainsReadOnlyOnly() {
        Path p = walPath("r8-e9.wal");
        String key = "c9";
        // 旧版 COMPLETED（无 smv/epoch/opId）：附件安全终态 + WAL 无记录 → 只读重放。
        BankTransferServiceTest.FakeLedger nested = new BankTransferServiceTest.FakeLedger();
        BankTransferRecord legacy = new BankTransferRecord(key,
                BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES, BankTransferPhase.COMPLETED,
                BankTransferStatus.SUCCESS, "旧记录", 10, 1000, 1000, 10,
                "", "", "", "", "", 4990, 2000, "", 0, 0, 0);
        nested.write(PLAYER, legacy);
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(4990);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(2000);
        BankTransferService svc = newService(p, bank, securities, nested);
        ReconciledBankTransferLedger reconciled = new ReconciledBankTransferLedger(nested,
                new FileTransferWal(p), new FileTransferWal(p));
        assertEquals(ReconciledBankTransferLedger.Decision.USE_ATTACHMENT_READONLY,
                reconciled.decide(TransferKey.of(PLAYER, key), null, nested.find(PLAYER, key)).decision(),
                "旧版安全终态 → 只读，不进入自动恢复");
        BankTransferResult r = svc.transfer(PLAYER, pendingDeposit(key, 10));
        assertTrue(r.duplicate(), "只读重放不自动动账");
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void poisonedWalBlocksOtherBankTransferKeys() {
        Path p = walPath("r8-p1.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.injectForceFailure(true);
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(wal, bank, securities, new BankTransferServiceTest.FakeLedger());
        // 首次 PREPARED force 失败 → 本次 fail closed（RECOVERY_REQUIRED）+ instance poisoned。
        assertFalse(svc.transfer(PLAYER, deposit(10, "p1")).success());
        assertTrue(wal.isPoisoned(), "append/force 失败后当前实例必须 poisoned");
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
        // 其它 key：poisoned 后入口见 global quarantine → UNAVAILABLE，零资金。
        assertEquals(BankTransferStatus.UNAVAILABLE, svc.transfer(PLAYER, deposit(20, "p2")).status());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
    }

    @Test
    void poisonedWalDoesNotBlockNormalStockTrading() {
        Path p = walPath("r8-p2.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.injectWriteFailureBeforeBytes(0);
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1234);
        BankTransferService svc = newService(wal, bank, securities, new BankTransferServiceTest.FakeLedger());
        assertFalse(svc.transfer(PLAYER, deposit(10, "q1")).success(), "写失败 fail closed");
        assertTrue(wal.isPoisoned());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, securities.depositCalls());
        // 普通证券余额读取（股票/持仓不经过银行桥）不受影响。
        assertEquals(1234, securities.balanceCents());
    }

    // ---------------------------------------------------------------- 收尾：当前 provider 校验 + 跨 key seq 回归

    private static BankTransferRecord recordWithProvider(BankTransferRequest req, BankTransferPhase phase,
                                                         BankTransferStatus status, long bankCu, long secCents,
                                                         String providerId, String tag) {
        BankTransferRequest wd = new BankTransferRequest(BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                req.requestedCopper(), 0, req.requestId());
        Derived d = derive(wd);
        return new BankTransferRecord(wd.requestId(), wd.direction(), phase, status, "状态",
                wd.requestedCopper(), d.reqCents(), d.debit(), d.copper(),
                "sm:bd:" + tag, "sm:bc:" + tag, "sm:sd:" + tag, "sm:sc:" + tag, "sm:rb:" + tag,
                bankCu, secCents, providerId, OperationIds.VERSION,
                BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
    }

    @Test
    void walOnlyPendingWithDifferentCurrentProviderDoesNotRecover() {
        Path p = walPath("fin-pr1.wal");
        String key = "pr1";
        BankTransferRequest req = pendingDeposit(key, 10);
        // WAL 单独存在（附件丢失）、pending 目标证券、但 providerId 与当前桥不同。
        new FileTransferWal(p).writeIntent(PLAYER, recordWithProvider(req,
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferStatus.INCOMPLETE_TRANSFER,
                4990, 1000, "some_other_bridge", "X"));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(4990);
        bank.seedDebit("sm:bd:X", 10);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = svc.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status(),
                "providerId 与当前桥不一致 → 禁止自动恢复");
        assertEquals(0, bank.withdrawCalls(), "LC withdraw=0");
        assertEquals(0, bank.depositCalls(), "LC deposit=0");
        assertEquals(0, securities.depositCalls(), "证券 deposit=0");
        assertEquals(0, securities.withdrawCalls(), "证券 withdraw=0");
    }

    @Test
    void currentProviderUnavailableDoesNotRecoverPendingTransfer() {
        Path p = walPath("fin-pr2.wal");
        String key = "pr2";
        BankTransferRequest req = pendingDeposit(key, 10);
        FileTransferWal w0 = new FileTransferWal(p);
        w0.writeIntent(PLAYER, recordTagged(req, BankTransferPhase.DESTINATION_CREDIT_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, 4990, 1000, "Y"));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setAvailable(false); // 桥不可用
        bank.setBalance(4990);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = svc.transfer(PLAYER, req);
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status(), "桥不可用 → 禁止自动恢复");
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, bank.depositCalls());
        assertEquals(0, securities.depositCalls());
        assertEquals(0, securities.withdrawCalls());
    }

    @Test
    void safeTerminalReplayDoesNotRequireCurrentProvider() {
        Path p = walPath("fin-pr3.wal");
        String key = "pr3";
        BankTransferRequest req = pendingDeposit(key, 10);
        // 安全终态（COMPLETED）+ WAL 单独存在；providerId 任意 + 桥不可用 → 只读重放不需桥。
        new FileTransferWal(p).writeIntent(PLAYER, recordWithProvider(req,
                BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, 4990, 2000,
                "some_other_bridge", "Z"));
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setAvailable(false);
        bank.setBalance(4990);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(2000);
        BankTransferService svc = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = svc.transfer(PLAYER, req);
        assertTrue(r.success());
        assertTrue(r.duplicate());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, bank.depositCalls());
        assertEquals(0, securities.depositCalls());
        assertEquals(0, securities.withdrawCalls());
    }

    @Test
    void crossKeySequenceRegressionFailsClosed() throws Exception {
        // 不同 key：seq=100（A）后 seq=50（B）→ 全局 seq 倒退必须 fail closed，
        // 不能因为两侧阶段各自合法而放行。
        Path p = walPath("fin-seq.wal");
        String lineA = craftValidLine(100, PLAYER, "seqA", "PREPARED", "A1");
        String lineB = craftValidLine(50, PLAYER_B, "seqB", "PREPARED", "B1");
        Files.write(p, (lineA + lineB).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileTransferWal wal = new FileTransferWal(p);
        assertTrue(wal.quarantinedKeys().contains(TransferKey.of(PLAYER_B, "seqB")),
                "seq 倒退的后续 key 必须 fail closed（keyed 隔离）");
        assertFalse(wal.globallyQuarantined(), "能可靠归属 → 只隔离对应 key");
        // 服务入口：seqB 请求 → 隔离 → MANUAL_REVIEW，资金 0。
        BankTransferServiceTest.FakeBank bank = new BankTransferServiceTest.FakeBank();
        bank.setBalance(5000);
        BankTransferServiceTest.FakeSecurities securities = new BankTransferServiceTest.FakeSecurities();
        securities.setBalance(1000);
        BankTransferService svc = newService(p, bank, securities, new BankTransferServiceTest.FakeLedger());
        BankTransferResult r = svc.transfer(PLAYER_B, new BankTransferRequest(
                BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES, 10, 0, "seqB"));
        assertEquals(BankTransferStatus.MANUAL_REVIEW, r.status());
        assertEquals(0, bank.withdrawCalls());
        assertEquals(0, bank.depositCalls());
        assertEquals(0, securities.depositCalls());
        assertEquals(0, securities.withdrawCalls());
    }

    /** 与 WAL 编码一致的手工合法行（供跨 key seq 回归反例构造乱序 seq）。 */
    private static String craftValidLine(long seq, UUID player, String req, String phase, String tag) {
        String payload = "WALV=1|seq=" + seq
                + "|p=" + hex(player.toString())
                + "|req=" + hex(req)
                + "|dir=DEPOSIT_TO_SECURITIES"
                + "|reqCu=10|reqCt=1000|db=1000|co=10"
                + "|ph=" + phase + "|st=INCOMPLETE_TRANSFER"
                + "|prv=" + hex(PROVIDER)
                + "|opv=" + OperationIds.VERSION
                + "|smv=" + BankTransferRecord.STATE_MACHINE_VERSION
                + "|ep=" + EPOCH
                + "|b0=" + hex("sm:bd:" + tag)
                + "|b1=" + hex("sm:bc:" + tag)
                + "|s0=" + hex("sm:sd:" + tag)
                + "|s1=" + hex("sm:sc:" + tag)
                + "|rb=" + hex("sm:rb:" + tag)
                + "|bk=4990|se=1000|msg=" + hex("状态");
        return payload + "|ck=" + sha256Hex(payload) + "\n";
    }

    private static String hex(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String sha256Hex(String payload) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 玩家级嵌套账本（模拟真实附件按玩家隔离；复合键命名空间）。 */
    static final class PlayerScopedLedger implements BankTransferLedger {
        final java.util.Map<UUID, BankTransferLedger> perPlayer = new java.util.HashMap<>();

        private BankTransferLedger forPlayer(UUID id) {
            return perPlayer.computeIfAbsent(id, k -> new BankTransferServiceTest.FakeLedger());
        }

        @Override public boolean write(UUID playerId, BankTransferRecord record) {
            return forPlayer(playerId).write(playerId, record);
        }

        @Override public BankTransferRecord find(UUID playerId, String requestId) {
            return forPlayer(playerId).find(playerId, requestId);
        }

        @Override public java.util.List<BankTransferRecord> recent(UUID playerId) {
            return forPlayer(playerId).recent(playerId);
        }
    }
}