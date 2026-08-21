package com.tanrunn.stockmarket.api;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端权威的银行 ⇄ 证券转账执行器（纯逻辑，无 Minecraft 依赖，可完整单测）。
 *
 * <p>兑换规则：「1 证券资金 = 1 铜币」。入金 N 铜币 → 证券内部 +N*100 cents（显示 +N）；
 * 出金请求 R cents → 向上取整到 {@code copper=ceil(R/100)} 铜币，证券<b>实际扣</b>
 * {@code copper*100} cents（与到账铜币一致，防小数出金增发）。</p>
 *
 * <p><b>write-ahead 阶段（v2）</b>：任何资金副作用之前先持久化“意图”（WAL force +
 * 账本 upsert，且<b>每次写完都校验返回值</b>，失败即 fail closed、零后续资金调用）：
 * <pre>
 * 入金：PREPARED → LC 扣款 → SOURCE_DEBITED → DESTINATION_CREDIT_PENDING → 证券入账
 *       → DESTINATION_CREDITED → COMPLETED
 *       证券失败 → COMPENSATION_PENDING → LC 补偿 → COMPENSATED / COMPENSATION_FAILED
 * 出金：PREPARED → 证券扣款 → SOURCE_DEBITED → DESTINATION_CREDIT_PENDING → LC 入账
 *       → DESTINATION_CREDITED → COMPLETED
 *       LC 失败 → COMPENSATION_PENDING → 证券补偿 → COMPENSATED / COMPENSATION_FAILED
 * 明确未动账失败：REJECTED（安全终态，建立墓碑，可只读重放）
 * </pre></p>
 *
 * <p><b>恢复（不自动调用 LC）</b>：LC 内存幂等账本可能 LRU 淘汰（同 runtimeEpoch 也不能证明
 * opId 仍在），runtimeEpoch 仅作审计。恢复只允许：
 * <ul>
 *   <li>DESTINATION_CREDIT_PENDING 且目标为证券 → 用账本内 opSecuritiesCredit 持久幂等重试；</li>
 *   <li>COMPENSATION_PENDING 且补偿目标为证券 → 用账本内 opRollback 持久幂等重试；</li>
 *   <li>目标或补偿为 LC、旧版 SOURCE_DEBITED、字段畸形/版本不符 → MANUAL_REVIEW，零资金调用。</li>
 * </ul></p>
 */
public final class BankTransferService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BankTransferService.class);

    /** 证券侧流水来源标识（出金必须注册为受信任来源）。 */
    public static final String SOURCE = "server_menu_lc_bank";

    private final CurrencyBridge bridge;
    private final Securities securities;
    private final BankTransferLedger ledger;
    private final Wal wal;
    private final long maxCopper;
    private final long maxSecuritiesCents;
    private final long runtimeEpoch;

    public interface Securities {
        boolean isAvailable(UUID playerId);

        long balanceCents(UUID playerId);

        TransferOutcome deposit(UUID playerId, long cents, String source, String reason, String requestId);

        TransferOutcome withdraw(UUID playerId, long cents, String source, String reason, String requestId);
    }

    /** 证券侧单步操作结果。 */
    public record TransferOutcome(boolean success, long balanceCents, String message) {
    }

    /** 本次转账用到的内部 opId 集合（恢复一律从账本取，禁止现场重算替换）。 */
    private record Ops(String opBankDebit, String opBankCredit,
                       String opSecuritiesDebit, String opSecuritiesCredit,
                       String opRollback) {
        static Ops fromRecord(BankTransferRecord r) {
            return new Ops(r.opBankDebit(), r.opBankCredit(), r.opSecuritiesDebit(),
                    r.opSecuritiesCredit(), r.opRollback());
        }
    }

    /** 由请求推导的精确金额（出金取整后）。 */
    private record Derived(long requestedSecuritiesCents, long actualDebitCents, long copper) {
    }

    public BankTransferService(CurrencyBridge bridge, Securities securities,
                               BankTransferLedger ledger, long maxCopper, long maxSecuritiesCents,
                               long runtimeEpoch) {
        this(bridge, securities, ledger, null, maxCopper, maxSecuritiesCents, runtimeEpoch);
    }

    public BankTransferService(CurrencyBridge bridge, Securities securities,
                               BankTransferLedger ledger, Wal wal, long maxCopper, long maxSecuritiesCents,
                               long runtimeEpoch) {
        this.bridge = bridge;
        this.securities = securities;
        this.ledger = ledger;
        this.wal = wal;
        this.maxCopper = maxCopper;
        this.maxSecuritiesCents = maxSecuritiesCents;
        this.runtimeEpoch = runtimeEpoch;
    }

    // ---------------------------------------------------------------- entry

    public BankTransferResult transfer(UUID playerId, BankTransferRequest request) {
        if (playerId == null || request == null) {
            return BankTransferResult.failure(BankTransferStatus.UNAVAILABLE,
                    "请求无效", 0, 0, 0, 0, 0, 0, "");
        }
        BankTransferStatus invalid = request.validateStatus(maxCopper, maxSecuritiesCents);
        if (invalid != null) {
            return BankTransferResult.failure(invalid, "请求参数无效",
                    safeBankBalance(playerId), safeSecuritiesBalance(playerId),
                    request.requestedCopper(), request.requestedSecuritiesCents(),
                    0, 0, request.requestId());
        }

        Derived derived;
        try {
            derived = derive(request);
        } catch (ArithmeticException | IllegalArgumentException e) {
            return BankTransferResult.failure(BankTransferStatus.INVALID_AMOUNT,
                    "金额超出可用范围", safeBankBalance(playerId), safeSecuritiesBalance(playerId),
                    request.requestedCopper(), request.requestedSecuritiesCents(), 0, 0, request.requestId());
        }

        String rid = request.requestId();

        // 账本重放 / 冲突 / 恢复（组合账本：WAL keyed/global 隔离、WAL 最新、附件详细、附件墓碑）
        final BankTransferRecord existing;
        try {
            existing = ledger.find(playerId, rid);
        } catch (BankTransferBlockedException e) {
            // WAL 隔离：global → UNAVAILABLE（银行转账全部 fail closed）；keyed → MANUAL_REVIEW。
            // 两种情况都零资金调用，不调用 LC/证券资金。
            LOGGER.error("[StockMarket] bank transfer blocked by WAL: global={} requestId={}",
                    e.global(), shortHashForLog(rid));
            BankTransferStatus blockedStatus = e.global()
                    ? BankTransferStatus.UNAVAILABLE : BankTransferStatus.MANUAL_REVIEW;
            return BankTransferResult.failure(blockedStatus,
                    e.global() ? "银行转账系统隔离中（资金记录完整性未知），请联系管理员"
                            : "该转账被隔离，需人工审计",
                    safeBankBalance(playerId), safeSecuritiesBalance(playerId),
                    request.requestedCopper(), request.requestedSecuritiesCents(),
                    derived.actualDebitCents(), derived.copper(), rid);
        }
        if (existing != null) {
            if (existing.direction() != request.direction()
                    || existing.requestedCopper() != (request.isDepositToSecurities() ? request.requestedCopper() : 0)
                    || existing.requestedSecuritiesCents() != derived.requestedSecuritiesCents()
                    || existing.actualDebitCents() != derived.actualDebitCents()
                    || existing.copperAmount() != derived.copper()) {
                return BankTransferResult.failure(BankTransferStatus.REQUEST_CONFLICT,
                        "同一请求标识已用于另一笔方向或金额",
                        existing.bankBalanceCopper(), existing.securitiesBalanceCents(),
                        existing.requestedCopper(), existing.requestedSecuritiesCents(),
                        existing.actualDebitCents(), existing.copperAmount(), rid);
            }
            return replayOrResume(playerId, request, derived, existing, Ops.fromRecord(existing));
        }

        if (bridge == null || !bridge.isAvailable()) {
            return BankTransferResult.failure(BankTransferStatus.UNAVAILABLE,
                    "银行桥接不可用", safeBankBalance(playerId), safeSecuritiesBalance(playerId),
                    request.requestedCopper(), request.requestedSecuritiesCents(),
                    derived.actualDebitCents(), derived.copper(), rid);
        }
        if (!securities.isAvailable(playerId)) {
            return BankTransferResult.failure(BankTransferStatus.SECURITIES_ERROR,
                    "证券账户暂不可用", safeBankBalance(playerId), safeSecuritiesBalance(playerId),
                    request.requestedCopper(), request.requestedSecuritiesCents(),
                    derived.actualDebitCents(), derived.copper(), rid);
        }

        String provider = bridge != null ? bridge.id() : "";
        String direction = request.direction().name();
        Ops ops = new Ops(
                OperationIds.generate(OperationIds.SM_BANK_DEBIT, provider, SOURCE, "bank_debit", rid, direction),
                OperationIds.generate(OperationIds.SM_BANK_CREDIT, provider, SOURCE, "bank_credit", rid, direction),
                OperationIds.generate(OperationIds.SM_SECURITIES_DEBIT, provider, SOURCE, "sec_debit", rid, direction),
                OperationIds.generate(OperationIds.SM_SECURITIES_CREDIT, provider, SOURCE, "sec_credit", rid, direction),
                OperationIds.generate(OperationIds.SM_ROLLBACK, provider, SOURCE,
                        request.isDepositToSecurities() ? "rollback_bank" : "rollback_securities",
                        rid, direction));

        return switch (request.direction()) {
            case DEPOSIT_TO_SECURITIES -> executeDeposit(playerId, request, derived, ops);
            case WITHDRAW_TO_BANK -> executeWithdraw(playerId, request, derived, ops);
        };
    }

    // ---------------------------------------------------------------- write-ahead helpers

    /** 先 WAL force、后账本 upsert；两者都必须成功才可继续资金副作用。 */
    private boolean persistent(UUID playerId, BankTransferRecord record) {
        if (wal != null && !wal.writeIntent(playerId, record)) {
            LOGGER.error("[StockMarket] WAL write failed: requestId={} phase={}", record.requestId(), record.phase());
            return false;
        }
        return ledger.write(playerId, record);
    }

    /** write-ahead 失败：fail closed，返回 RECOVERY_REQUIRED（零后续资金调用）。 */
    private BankTransferResult writeFail(UUID playerId, BankTransferRequest request, Derived derived,
                                         String what, BankTransferRecord record) {
        LOGGER.error("[StockMarket] transfer write-ahead failed for {}: requestId={} phase={} status={}",
                what, record.requestId(), record.phase(), record.status());
        return BankTransferResult.failure(BankTransferStatus.RECOVERY_REQUIRED,
                "转账状态未能持久化（" + what + "），请稍后重试或联系管理员",
                record.bankBalanceCopper(), record.securitiesBalanceCents(),
                request.requestedCopper(), request.requestedSecuritiesCents(),
                derived.actualDebitCents(), derived.copper(), request.requestId());
    }

    // ---------------------------------------------------------------- replay / resume (v2：不自动调 LC)

    private BankTransferResult replayOrResume(UUID playerId, BankTransferRequest request, Derived derived,
                                              BankTransferRecord existing, Ops persistedOps) {
        if (!BankTransferRecordValidator.isWellFormed(existing)) {
            return manualReview(playerId, existing, "转账记录数据异常或版本不符，需人工审计");
        }

        switch (existing.phase()) {
            case COMPLETED:
            case COMPENSATED:
            case COMPENSATION_FAILED:
            case MANUAL_REVIEW:
            case REJECTED:
                return BankTransferResult.stored(existing, true);
            case DESTINATION_CREDITED:
                BankTransferRecord completed2 = updated(existing, BankTransferPhase.COMPLETED,
                        BankTransferStatus.SUCCESS, "转账成功",
                        existing.bankBalanceCopper(), existing.securitiesBalanceCents());
                if (!persistent(playerId, completed2)) {
                    return writeFail(playerId, request, derived, "COMPLETED", completed2);
                }
                return BankTransferResult.success(completed2, true);
            case SOURCE_DEBITED:
                return manualReview(playerId, existing,
                        "来源已扣但目标意图未持久化（旧版阶段），需人工审计");
            case DESTINATION_CREDIT_PENDING:
                if (!request.isDepositToSecurities()) {
                    return manualReview(playerId, existing, "等待向 LC 入账，无法证明是否已到账，需人工审计");
                }
                return resumeDepositPending(playerId, request, derived, existing, persistedOps);
            case COMPENSATION_PENDING:
                if (request.isDepositToSecurities()) {
                    return manualReview(playerId, existing, "等待向 LC 补偿，无法证明是否已退款，需人工审计");
                }
                return resumeCompensationPending(playerId, request, derived, existing, persistedOps);
            case PREPARED:
            default:
                if (definitiveNoMoveStatus(existing.status())) {
                    return BankTransferResult.stored(existing, true);
                }
                return manualReview(playerId, existing, "转账在预提交阶段被中断，状态不确定，需人工审计");
        }
    }

    // ---------------------------------------------------------------- resume (证券侧持久幂等可重试)

    /** DESTINATION_CREDIT_PENDING + 目标=证券：用账本内 opSecuritiesCredit 幂等重试。 */
    private BankTransferResult resumeDepositPending(UUID playerId, BankTransferRequest request, Derived derived,
                                                    BankTransferRecord existing, Ops ops) {
        if (existing.direction() != BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES) {
            return manualReview(playerId, existing, "目标非证券，无法安全恢复，需人工审计");
        }
        // 收尾：可能自动向证券入账前，必须校验当前 provider 与记录一致且可用。
        if (!currentProviderRecoverable(existing)) {
            return manualReview(playerId, existing,
                    "当前银行桥 provider 与记录不一致或不可用，禁止自动恢复（资金 0）");
        }
        if (securities == null || !securities.isAvailable(playerId)) {
            return recoveryRequired(playerId, existing, "证券账户暂不可用，转账待恢复");
        }
        TransferOutcome deposited = securities.deposit(playerId, derived.actualDebitCents(), SOURCE,
                "入金到证券账户", ops.opSecuritiesCredit);
        long bankAfter = safeBankBalance(playerId);
        if (deposited.success()) {
            BankTransferRecord cred = updated(existing, BankTransferPhase.DESTINATION_CREDITED,
                    BankTransferStatus.INCOMPLETE_TRANSFER, "证券已入账", bankAfter, deposited.balanceCents());
            if (!persistent(playerId, cred)) {
                return writeFail(playerId, request, derived, "DESTINATION_CREDITED", cred);
            }
            BankTransferRecord done = updated(cred, BankTransferPhase.COMPLETED,
                    BankTransferStatus.SUCCESS, "入金成功", bankAfter, deposited.balanceCents());
            if (!persistent(playerId, done)) {
                return writeFail(playerId, request, derived, "COMPLETED", done);
            }
            return BankTransferResult.success(done, true);
        }
        BankTransferRecord pendingComp = updated(existing, BankTransferPhase.COMPENSATION_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, "证券入账仍失败，补偿目标为 LC",
                bankAfter, deposited.balanceCents());
        if (!persistent(playerId, pendingComp)) {
            return writeFail(playerId, request, derived, "COMPENSATION_PENDING", pendingComp);
        }
        return manualReview(playerId, existing, "证券入账失败且补偿目标为 LC，无法自动完成，需人工审计");
    }

    /** COMPENSATION_PENDING + 补偿目标=证券：用账本内 opRollback 幂等重试（不重复补偿）。 */
    private BankTransferResult resumeCompensationPending(UUID playerId, BankTransferRequest request,
                                                         Derived derived, BankTransferRecord existing, Ops ops) {
        if (existing.direction() != BankTransferRequest.Direction.WITHDRAW_TO_BANK) {
            return manualReview(playerId, existing, "补偿目标非证券，无法安全恢复，需人工审计");
        }
        // 收尾：可能自动向证券补偿前，必须校验当前 provider 与记录一致且可用。
        if (!currentProviderRecoverable(existing)) {
            return manualReview(playerId, existing,
                    "当前银行桥 provider 与记录不一致或不可用，禁止自动恢复（资金 0）");
        }
        if (securities == null || !securities.isAvailable(playerId)) {
            return recoveryRequired(playerId, existing, "证券账户暂不可用，补偿待恢复");
        }
        TransferOutcome compensation = securities.deposit(playerId, derived.actualDebitCents(), SOURCE,
                "提现失败补偿", ops.opRollback);
        if (compensation.success()) {
            BankTransferRecord compensated = updated(existing, BankTransferPhase.COMPENSATED,
                    BankTransferStatus.BANK_ERROR, "银行入账失败，金额已退回证券账户",
                    existing.bankBalanceCopper(), compensation.balanceCents());
            if (!persistent(playerId, compensated)) {
                return writeFail(playerId, request, derived, "COMPENSATED", compensated);
            }
            return BankTransferResult.stored(compensated, true);
        }
        BankTransferRecord failed = updated(existing, BankTransferPhase.COMPENSATION_FAILED,
                BankTransferStatus.COMPENSATION_FAILED,
                "银行入账失败且证券补偿失败，请联系管理员审计",
                existing.bankBalanceCopper(), compensation.balanceCents());
        if (!persistent(playerId, failed)) {
            return writeFail(playerId, request, derived, "COMPENSATION_FAILED", failed);
        }
        return BankTransferResult.stored(failed, true);
    }

    // ---------------------------------------------------------------- deposit flow

    private BankTransferResult executeDeposit(UUID playerId, BankTransferRequest request,
                                              Derived derived, Ops ops) {
        long copper = derived.copper();
        long secCents = derived.actualDebitCents();

        // 1) PREPARED（write-ahead：落盘成功才能动账；容量/防重回写失败 → fail closed）
        BankTransferRecord prepared = newTransfer(playerId, request, derived, BankTransferPhase.PREPARED,
                BankTransferStatus.INCOMPLETE_TRANSFER, "转账已受理", ops,
                safeBankBalance(playerId), safeSecuritiesBalance(playerId));
        if (!persistent(playerId, prepared)) {
            return writeFail(playerId, request, derived, "PREPARED", prepared);
        }

        // 2) 扣来源（LC）
        long bankBalance = bridge.balanceCopper(playerId);
        if (bankBalance < copper) {
            return finishCleanFailure(playerId, request, derived, prepared,
                    BankTransferStatus.INSUFFICIENT_FUNDS, "银行余额不足");
        }
        BridgeResult withdrawn = bridge.withdraw(playerId, copper, SOURCE,
                "入金到证券账户", ops.opBankDebit);
        long bankAfter = bridge.balanceCopper(playerId);
        if (!withdrawn.success()) {
            // COMPENSATION_FAILED / 可能已扣款未退回：绝不能当“明确未动账失败”处理 → COMPENSATION_FAILED。
            if (withdrawn.status() == BridgeStatusCode.COMPENSATION_FAILED) {
                return bankCompensationFailedButProceed(playerId, request, derived, prepared,
                        withdrawn.actualCopper(), bankAfter, "银行扣款已发生且补偿失败");
            }
            return finishCleanFailure(playerId, request, derived, prepared,
                    mapWithdrawalFailure(withdrawn.status()), "银行扣款失败");
        }
        if (withdrawn.actualCopper() != copper) {
            return compensateBankAfterMismatch(playerId, request, derived, ops,
                    withdrawn.actualCopper(), safeSecuritiesBalance(playerId), prepared);
        }

        // 3) SOURCE_DEBITED
        BankTransferRecord sourceDebited = updated(prepared, BankTransferPhase.SOURCE_DEBITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, "已扣银行铜币，待证券入账",
                bankAfter, safeSecuritiesBalance(playerId));
        if (!persistent(playerId, sourceDebited)) {
            return writeFail(playerId, request, derived, "SOURCE_DEBITED", sourceDebited);
        }

        // 4) DESTINATION_CREDIT_PENDING（先写意图，再调证券入账）
        BankTransferRecord pending = updated(prepared, BankTransferPhase.DESTINATION_CREDIT_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, "即将向证券入账",
                bankAfter, safeSecuritiesBalance(playerId));
        if (!persistent(playerId, pending)) {
            return writeFail(playerId, request, derived, "DESTINATION_CREDIT_PENDING", pending);
        }

        // 5) 增目标（证券）
        TransferOutcome deposited = securities.deposit(playerId, secCents, SOURCE,
                "入金到证券账户", ops.opSecuritiesCredit);
        if (!deposited.success()) {
            return compensateAfterDestFailed(playerId, request, derived, ops,
                    deposited.balanceCents(), bankAfter, "入金失败补偿", prepared);
        }

        // 6) DESTINATION_CREDITED + COMPLETED
        BankTransferRecord destCredited = updated(prepared, BankTransferPhase.DESTINATION_CREDITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, "证券已入账",
                bankAfter, deposited.balanceCents());
        if (!persistent(playerId, destCredited)) {
            return writeFail(playerId, request, derived, "DESTINATION_CREDITED", destCredited);
        }
        BankTransferRecord completed = updated(prepared, BankTransferPhase.COMPLETED,
                BankTransferStatus.SUCCESS, "入金成功", bankAfter, deposited.balanceCents());
        if (!persistent(playerId, completed)) {
            return writeFail(playerId, request, derived, "COMPLETED", completed);
        }
        return BankTransferResult.success(completed, false);
    }

    // ---------------------------------------------------------------- withdraw flow

    private BankTransferResult executeWithdraw(UUID playerId, BankTransferRequest request,
                                               Derived derived, Ops ops) {
        long copper = derived.copper();
        long actualDebit = derived.actualDebitCents();

        BankTransferRecord prepared = newTransfer(playerId, request, derived, BankTransferPhase.PREPARED,
                BankTransferStatus.INCOMPLETE_TRANSFER, "转账已受理", ops,
                safeBankBalance(playerId), safeSecuritiesBalance(playerId));
        if (!persistent(playerId, prepared)) {
            return writeFail(playerId, request, derived, "PREPARED", prepared);
        }

        // 2) 扣来源（证券）
        long securitiesBalance = securities.balanceCents(playerId);
        if (securitiesBalance < actualDebit) {
            return finishCleanFailure(playerId, request, derived, prepared,
                    BankTransferStatus.INSUFFICIENT_FUNDS,
                    "证券余额不足支付向上取整后的实际扣款");
        }
        TransferOutcome withdrawn = securities.withdraw(playerId, actualDebit, SOURCE,
                "提现到银行", ops.opSecuritiesDebit);
        if (!withdrawn.success()) {
            return finishCleanFailure(playerId, request, derived, prepared,
                    BankTransferStatus.SECURITIES_ERROR, "证券账户扣款失败");
        }
        long securitiesAfter = withdrawn.balanceCents();

        BankTransferRecord sourceDebited = updated(prepared, BankTransferPhase.SOURCE_DEBITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, "证券已扣款，待银行入账",
                safeBankBalance(playerId), securitiesAfter);
        if (!persistent(playerId, sourceDebited)) {
            return writeFail(playerId, request, derived, "SOURCE_DEBITED", sourceDebited);
        }

        BankTransferRecord pending = updated(prepared, BankTransferPhase.DESTINATION_CREDIT_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, "即将向 LC 入账",
                safeBankBalance(playerId), securitiesAfter);
        if (!persistent(playerId, pending)) {
            return writeFail(playerId, request, derived, "DESTINATION_CREDIT_PENDING", pending);
        }

        // 5) 增目标（LC）
        BridgeResult deposited = bridge.deposit(playerId, copper, SOURCE,
                "提现到银行", ops.opBankCredit);
        long bankNow = bridge.balanceCopper(playerId);
        if (!deposited.success() || deposited.actualCopper() != copper) {
            return compensateAfterDestFailedSecurities(playerId, request, derived, ops,
                    securitiesAfter, bankNow, prepared);
        }

        BankTransferRecord destCredited = updated(prepared, BankTransferPhase.DESTINATION_CREDITED,
                BankTransferStatus.INCOMPLETE_TRANSFER, "银行已入账",
                bankNow, securitiesAfter);
        if (!persistent(playerId, destCredited)) {
            return writeFail(playerId, request, derived, "DESTINATION_CREDITED", destCredited);
        }
        BankTransferRecord completed = updated(prepared, BankTransferPhase.COMPLETED,
                BankTransferStatus.SUCCESS,
                "提现成功：证券扣 " + fmtAmount(actualDebit) + "，ATM 到账 " + copper + " 铜币",
                bankNow, securitiesAfter);
        if (!persistent(playerId, completed)) {
            return writeFail(playerId, request, derived, "COMPLETED", completed);
        }
        return BankTransferResult.success(completed, false);
    }

    // ---------------------------------------------------------------- compensation (先写意图)

    /** 入金目标（证券）失败：先写 COMPENSATION_PENDING，再把原铜币退回 LC。 */
    private BankTransferResult compensateAfterDestFailed(UUID playerId, BankTransferRequest request,
                                                         Derived derived, Ops ops,
                                                         long securitiesBalance, long bankAfterKnown,
                                                         String reason, BankTransferRecord baseAtPending) {
        BankTransferRecord pendingComp = updated(baseAtPending, BankTransferPhase.COMPENSATION_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, "即将补偿 LC", bankAfterKnown, securitiesBalance);
        if (!persistent(playerId, pendingComp)) {
            return writeFail(playerId, request, derived, "COMPENSATION_PENDING", pendingComp);
        }
        long copper = derived.copper();
        BridgeResult compensation = (bridge == null)
                ? null : bridge.deposit(playerId, copper, SOURCE, reason, ops.opRollback);
        long bankNow = bridge == null ? bankAfterKnown : bridge.balanceCopper(playerId);
        boolean ok = compensation != null && compensation.success() && compensation.actualCopper() == copper;
        BankTransferRecord terminal;
        if (ok) {
            terminal = updated(baseAtPending, BankTransferPhase.COMPENSATED,
                    BankTransferStatus.SECURITIES_ERROR, "证券入账失败，金额已退回银行",
                    bankNow, securitiesBalance);
        } else {
            terminal = updated(baseAtPending, BankTransferPhase.COMPENSATION_FAILED,
                    BankTransferStatus.COMPENSATION_FAILED,
                    "证券入账失败且银行补偿失败，请联系管理员审计", bankNow, securitiesBalance);
        }
        if (!persistent(playerId, terminal)) {
            return writeFail(playerId, request, derived,
                    terminal.phase().name(), terminal);
        }
        return BankTransferResult.stored(terminal, false);
    }

    /** 出金目标（LC）失败：先写 COMPENSATION_PENDING，再向证券补偿完整实际扣款。 */
    private BankTransferResult compensateAfterDestFailedSecurities(UUID playerId, BankTransferRequest request,
                                                                   Derived derived, Ops ops,
                                                                   long securitiesAfterWithdraw,
                                                                   long bankNow, BankTransferRecord baseAtPending) {
        BankTransferRecord pendingComp = updated(baseAtPending, BankTransferPhase.COMPENSATION_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, "即将补偿证券", bankNow, securitiesAfterWithdraw);
        if (!persistent(playerId, pendingComp)) {
            return writeFail(playerId, request, derived, "COMPENSATION_PENDING", pendingComp);
        }
        long actualDebit = derived.actualDebitCents();
        TransferOutcome compensation = securities.deposit(playerId, actualDebit, SOURCE,
                "提现失败补偿", ops.opRollback);
        BankTransferRecord terminal;
        if (compensation.success()) {
            terminal = updated(baseAtPending, BankTransferPhase.COMPENSATED,
                    BankTransferStatus.BANK_ERROR, "银行入账失败，金额已退回证券账户",
                    bankNow, compensation.balanceCents());
        } else {
            terminal = updated(baseAtPending, BankTransferPhase.COMPENSATION_FAILED,
                    BankTransferStatus.COMPENSATION_FAILED,
                    "银行入账失败且证券补偿失败，请联系管理员审计", bankNow, compensation.balanceCents());
        }
        if (!persistent(playerId, terminal)) {
            return writeFail(playerId, request, derived, terminal.phase().name(), terminal);
        }
        return BankTransferResult.stored(terminal, false);
    }

    /** 防御：银行扣款金额不符 → 先写 COMPENSATION_PENDING，再把实扣金额退回 LC。 */
    private BankTransferResult compensateBankAfterMismatch(UUID playerId, BankTransferRequest request,
                                                           Derived derived, Ops ops,
                                                           long actualWithdrawn, long securitiesBalance,
                                                           BankTransferRecord prepared) {
        BankTransferRecord pendingComp = updated(prepared, BankTransferPhase.COMPENSATION_PENDING,
                BankTransferStatus.INCOMPLETE_TRANSFER, "即将按实扣金额补偿 LC",
                safeBankBalance(playerId), securitiesBalance);
        if (!persistent(playerId, pendingComp)) {
            return writeFail(playerId, request, derived, "COMPENSATION_PENDING", pendingComp);
        }
        BridgeResult compensation = (bridge == null)
                ? null : bridge.deposit(playerId, actualWithdrawn, SOURCE, "金额不符补偿", ops.opRollback);
        long bankNow = bridge == null ? 0 : bridge.balanceCopper(playerId);
        boolean ok = compensation != null && compensation.success() && compensation.actualCopper() == actualWithdrawn;
        BankTransferRecord terminal;
        if (ok) {
            terminal = updated(prepared, BankTransferPhase.COMPENSATED,
                    BankTransferStatus.BANK_ERROR, "银行扣款异常，金额已退回银行",
                    bankNow, securitiesBalance);
        } else {
            terminal = updated(prepared, BankTransferPhase.COMPENSATION_FAILED,
                    BankTransferStatus.COMPENSATION_FAILED,
                    "银行扣款异常且补偿失败，请联系管理员审计", bankNow, securitiesBalance);
        }
        if (!persistent(playerId, terminal)) {
            return writeFail(playerId, request, derived, terminal.phase().name(), terminal);
        }
        return BankTransferResult.stored(terminal, false);
    }

    /**
     * 银行扣款已发生且 BC 标称补偿失败：证据不足/可能已扣未退，<b>绝不能 REJECTED</b>。
     * 直接落 COMPENSATION_FAILED 终态（保存实扣金额于审计消息、余额快照），建立墓碑，
     * 后续重放只读、资金调用 0；coordinator 应输出管理员审计日志。
     */
    private BankTransferResult bankCompensationFailedButProceed(UUID playerId, BankTransferRequest request,
                                                                Derived derived, BankTransferRecord baseAtPending,
                                                                long actualCopper, long bankAfter, String reason) {
        BankTransferRecord terminal = updated(baseAtPending, BankTransferPhase.COMPENSATION_FAILED,
                BankTransferStatus.COMPENSATION_FAILED,
                reason + "（疑似已扣 " + actualCopper + " 铜币未退回，需人工审计）",
                bankAfter, safeSecuritiesBalance(playerId));
        if (!persistent(playerId, terminal)) {
            return writeFail(playerId, request, derived, "COMPENSATION_FAILED", terminal);
        }
        return BankTransferResult.stored(terminal, false);
    }

    // ---------------------------------------------------------------- helpers

    private static String shortHashForLog(String value) {
        if (value == null || value.isEmpty()) {
            return "?";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "?";
        }
    }

    private static String fmtAmount(long cents) {
        long yuan = Math.floorDiv(cents, 100);
        long remainder = Math.floorMod(cents, 100);
        return yuan + "." + (remainder < 10 ? "0" : "") + remainder;
    }

    /** 明确的未动账失败：REJECTED 终态（安全终态，建立墓碑，可只读重放原失败）。 */
    private BankTransferResult finishCleanFailure(UUID playerId, BankTransferRequest request,
                                                  Derived derived, BankTransferRecord prepared,
                                                  BankTransferStatus status, String message) {
        BankTransferRecord rejected = updated(prepared, BankTransferPhase.REJECTED,
                status, message, safeBankBalance(playerId), safeSecuritiesBalance(playerId));
        if (!persistent(playerId, rejected)) {
            return writeFail(playerId, request, derived, "REJECTED", rejected);
        }
        return BankTransferResult.stored(rejected, false);
    }

    private BankTransferResult recoveryRequired(UUID playerId, BankTransferRecord existing, String message) {
        return BankTransferResult.failure(BankTransferStatus.RECOVERY_REQUIRED, message,
                existing.bankBalanceCopper(), existing.securitiesBalanceCents(),
                existing.requestedCopper(), existing.requestedSecuritiesCents(),
                existing.actualDebitCents(), existing.copperAmount(), existing.requestId());
    }

    private BankTransferResult manualReview(UUID playerId, BankTransferRecord existing, String message) {
        BankTransferRecord updated = updated(existing, BankTransferPhase.MANUAL_REVIEW,
                BankTransferStatus.MANUAL_REVIEW, message,
                existing.bankBalanceCopper(), existing.securitiesBalanceCents());
        if (!persistent(playerId, updated)) {
            return BankTransferResult.failure(BankTransferStatus.RECOVERY_REQUIRED,
                    "转账状态未能持久化（MANUAL_REVIEW），请稍后重试或联系管理员",
                    updated.bankBalanceCopper(), updated.securitiesBalanceCents(),
                    updated.requestedCopper(), updated.requestedSecuritiesCents(),
                    updated.actualDebitCents(), updated.copperAmount(), updated.requestId());
        }
        return BankTransferResult.stored(updated, true);
    }

    private static Derived derive(BankTransferRequest request) {
        if (request.isDepositToSecurities()) {
            long copper = request.requestedCopper();
            long secCents = ExchangeRates.copperToSecuritiesCents(copper);
            return new Derived(secCents, secCents, copper);
        }
        long requested = request.requestedSecuritiesCents();
        long copper = ExchangeRates.securitiesCentsToCopperCeil(requested);
        long actualDebit = ExchangeRates.copperToSecuritiesCents(copper);
        return new Derived(requested, actualDebit, copper);
    }

    private BankTransferRecord newTransfer(UUID playerId, BankTransferRequest request,
                                           Derived derived, BankTransferPhase phase,
                                           BankTransferStatus status, String message,
                                           Ops ops, long bankBalanceCopper, long securitiesBalanceCents) {
        return new BankTransferRecord(request.requestId(), request.direction(), phase, status, message,
                request.isDepositToSecurities() ? request.requestedCopper() : 0,
                derived.requestedSecuritiesCents(), derived.actualDebitCents(), derived.copper(),
                ops.opBankDebit, ops.opBankCredit, ops.opSecuritiesDebit, ops.opSecuritiesCredit,
                ops.opRollback, bankBalanceCopper, securitiesBalanceCents,
                currentProviderId(), OperationIds.VERSION,
                BankTransferRecord.STATE_MACHINE_VERSION, currentRuntimeEpoch());
    }

    private String currentProviderId() {
        if (bridge == null) {
            return "";
        }
        String id = bridge.id();
        return id == null ? "" : id;
    }

    private long currentRuntimeEpoch() {
        return runtimeEpoch;
    }

    private BankTransferRecord updated(BankTransferRecord base, BankTransferPhase phase,
                                       BankTransferStatus status, String message,
                                       long bankBalanceCopper, long securitiesBalanceCents) {
        return new BankTransferRecord(base.requestId(), base.direction(), phase, status, message,
                base.requestedCopper(), base.requestedSecuritiesCents(), base.actualDebitCents(),
                base.copperAmount(), base.opBankDebit(), base.opBankCredit(),
                base.opSecuritiesDebit(), base.opSecuritiesCredit(), base.opRollback(),
                bankBalanceCopper, securitiesBalanceCents,
                base.providerId(), base.operationIdVersion(), base.stateMachineVersion(),
                base.runtimeEpoch());
    }

    private static boolean definitiveNoMoveStatus(BankTransferStatus status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case SUCCESS, COMPENSATION_FAILED, MANUAL_REVIEW, RECOVERY_REQUIRED -> true;
            case INSUFFICIENT_FUNDS, UNAVAILABLE, BANK_ERROR, SECURITIES_ERROR,
                    PARTIAL_OPERATION, RATE_LIMITED, QUARANTINED, WRONG_THREAD,
                    INVALID_AMOUNT, INVALID_REQUEST, REQUEST_CONFLICT -> true;
            case INCOMPLETE_TRANSFER -> false;
        };
    }

    private static BankTransferStatus mapWithdrawalFailure(BridgeStatusCode code) {
        return switch (code) {
            case INSUFFICIENT_FUNDS -> BankTransferStatus.INSUFFICIENT_FUNDS;
            case QUARANTINED -> BankTransferStatus.QUARANTINED;
            case WRONG_THREAD -> BankTransferStatus.WRONG_THREAD;
            case PARTIAL_OPERATION -> BankTransferStatus.PARTIAL_OPERATION;
            case COMPENSATION_FAILED -> BankTransferStatus.COMPENSATION_FAILED;
            case REQUEST_CONFLICT -> BankTransferStatus.REQUEST_CONFLICT;
            case UNAVAILABLE -> BankTransferStatus.UNAVAILABLE;
            default -> BankTransferStatus.BANK_ERROR;
        };
    }

    /**
     * 当前 provider 校验（收尾）：仅在“可能自动恢复证券资金”的路径前调用。
     * 桥必须存在、可用、id 非空且取 id 不抛异常，且与持久记录 providerId 一致；
     * 任何不满足 → 禁止自动恢复（MANUAL_REVIEW，LC/证券资金 0，不重算 opId）。
     * WAL 单独存在（附件丢失）时同样适用。安全终态只读重放不调用本方法。
     */
    private boolean currentProviderRecoverable(BankTransferRecord existing) {
        if (bridge == null || !bridge.isAvailable()) {
            return false;
        }
        final String id;
        try {
            id = bridge.id();
        } catch (RuntimeException e) {
            return false;
        }
        if (id == null || id.isBlank()) {
            return false;
        }
        return id.equals(existing.providerId());
    }

    private long safeBankBalance(UUID playerId) {
        if (bridge == null) {
            return 0;
        }
        try {
            return Math.max(0, bridge.balanceCopper(playerId));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private long safeSecuritiesBalance(UUID playerId) {
        if (securities == null) {
            return 0;
        }
        try {
            return Math.max(0, securities.balanceCents(playerId));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
