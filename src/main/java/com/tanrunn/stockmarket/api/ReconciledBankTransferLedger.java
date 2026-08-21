package com.tanrunn.stockmarket.api;

import java.util.UUID;

/**
 * 组合账本（第六/七/八轮）：玩家附件账本 + WAL 崩溃恢复视图的对账入口。
 *
 * <p><b>查找优先级</b>：WAL 全局隔离（抛 blocked(global)）→ WAL 该键隔离（抛 blocked(keyed)）→
 * WAL 最新与附件经 {@link #decide} 对账 → 附件详细/墓碑 → 新请求。WAL 中存在该键（即使附件
 * 缺失）绝不当作新请求；WAL 恢复一律使用 WAL 内持久化 opId。</p>
 *
 * <p><b>对账规则（第八轮）</b>：
 * <ol>
 *   <li>任一侧未过 {@link BankTransferRecordValidator} → MR；</li>
 *   <li>附件缺失、WAL 存在 → 采用 WAL 恢复；WAL 缺失、附件安全终态 → 只读；附件非安全 → MR；</li>
 *   <li>防重指纹（方向/金额）冲突 → MR；任一侧 MANUAL_REVIEW → 保守 MR；</li>
 *   <li><b>恢复身份</b>：若将采用的 WAL 是非安全终态（可能触发自动证券恢复），必须
 *       {@link BankTransferRecord#recoveryIdentityMatches}（providerId/版本/epoch/方向所需全部
 *       opId）；不一致 → MR；</li>
 *   <li>WAL 阶段在状态图上合法领先/等于附件 → 采用 WAL；附件领先/分支冲突/无法证明 → MR。</li>
 * </ol></p>
 *
 * <p><b>持久化隔离证据（第八轮）</b>：任何 MR_BLOCK 都必须在返回前把 keyed quarantine
 * marker 通过 {@link Wal#quarantineKey} append+flush+force 持久化；marker 跨重启/跨压缩保留、
 * 附件完全丢失后仍阻断该 key（绝不再变成新请求）；marker 持久化失败 → 抛 blocked(global)
 * （fail closed，不得依赖临时值继续用原 pending 自动恢复）。</p>
 */
public final class ReconciledBankTransferLedger implements BankTransferLedger {

    private final BankTransferLedger nested;
    private final Wal wal;
    private final WalRecoveryView recovery;

    public ReconciledBankTransferLedger(BankTransferLedger nested, Wal wal, WalRecoveryView recovery) {
        this.nested = nested;
        this.wal = wal;
        this.recovery = recovery;
    }

    public enum Decision {
        USE_WAL, USE_ATTACHMENT_READONLY, MANUAL_REVIEW_BLOCK, NEW_REQUEST
    }

    public record Result(Decision decision, BankTransferRecord record, String reason,
                         QuarantineReason quarantineReason) {
        public static Result useWal(BankTransferRecord r) {
            return new Result(Decision.USE_WAL, r, "", null);
        }

        public static Result readOnly(BankTransferRecord r) {
            return new Result(Decision.USE_ATTACHMENT_READONLY, r, "", null);
        }

        public static Result manualReview(BankTransferRecord r, String reason,
                                          QuarantineReason qr) {
            return new Result(Decision.MANUAL_REVIEW_BLOCK, r, reason, qr);
        }

        public static Result newRequest() {
            return new Result(Decision.NEW_REQUEST, null, "", null);
        }
    }

    /** 纯对账逻辑（find 与登录写回共用；global/keyed 阻断由调用方先行）。 */
    public Result decide(TransferKey key, BankTransferRecord walLatest, BankTransferRecord attach) {
        // 1) 记录自身合法性
        if (walLatest != null && !BankTransferRecordValidator.isWellFormed(walLatest)) {
            return Result.manualReview(walLatest, "WAL 记录非法（校验失败），需人工审计",
                    QuarantineReason.RECONCILIATION_WAL_ILLEGAL);
        }
        if (attach != null && !BankTransferRecordValidator.isWellFormed(attach)) {
            return Result.manualReview(attach, "附件记录非法（校验失败），需人工审计",
                    QuarantineReason.RECONCILIATION_ATTACHMENT_ILLEGAL);
        }
        // 2) 单侧存在
        if (walLatest != null && attach == null) {
            return Result.useWal(walLatest);
        }
        if (walLatest == null && attach != null) {
            return attach.isSafeTerminal()
                    ? Result.readOnly(attach)
                    : Result.manualReview(attach, "WAL 缺失但附件非安全终态（不一致），需人工审计",
                            QuarantineReason.RECONCILIATION_ATTACHMENT_NONSAFE);
        }
        if (walLatest == null) {
            return Result.newRequest();
        }
        // 3) 防重指纹冲突
        if (!walLatest.dedupMatches(attach)) {
            return Result.manualReview(walLatest, "WAL 与附件防重指纹冲突（方向/金额），需人工审计",
                    QuarantineReason.RECONCILIATION_FINGERPRINT_CONFLICT);
        }
        // 3b) 任一侧 MANUAL_REVIEW → 保守优先
        if (walLatest.phase() == BankTransferPhase.MANUAL_REVIEW
                || attach.phase() == BankTransferPhase.MANUAL_REVIEW) {
            return Result.manualReview(walLatest, "任一侧为 MANUAL_REVIEW，保守状态优先，需人工审计",
                    QuarantineReason.RECONCILIATION_MANUAL_ATTACHMENT);
        }
        // 4) WAL 阶段合法领先/等于附件 → 采用 WAL；若 WAL 为非安全终态（可能自动证券恢复），
        //    必须恢复身份严格一致。
        if (TransferPhases.canProgressTo(attach.phase(), walLatest.phase())) {
            if (!walLatest.isSafeTerminal()
                    && !walLatest.recoveryIdentityMatches(attach)) {
                return Result.manualReview(walLatest,
                        "可能触发自动证券恢复但恢复身份不一致（provider/版本/epoch/opId），需人工审计",
                        QuarantineReason.RECONCILIATION_RECOVERY_IDENTITY_CONFLICT);
            }
            return Result.useWal(walLatest);
        }
        // 5) 附件领先 / 不同分支 / 无法证明先后 → MR
        QuarantineReason qr = TransferPhases.divergent(walLatest.phase(), attach.phase())
                ? QuarantineReason.RECONCILIATION_DIVERGENT
                : QuarantineReason.RECONCILIATION_ATTACHMENT_AHEAD;
        String reason = qr == QuarantineReason.RECONCILIATION_DIVERGENT
                ? "WAL 与附件处于不同阶段分支，需人工审计"
                : "附件阶段领先 WAL 且无法证明先后，需人工审计";
        return Result.manualReview(walLatest, reason, qr);
    }

    /**
     * 把 MR_BLOCK 变成跨重启的持久隔离证据：写 keyed quarantine marker；已隔离则跳过。
     * marker 持久化失败 → 抛 blocked(global)（fail closed，不得继续自动恢复）。
     */
    private void persistReconciliationBlock(TransferKey key, Result result) {
        if (recovery.quarantinedKeys().contains(key)) {
            return; // 已有 marker/隔离
        }
        QuarantineReason qr = result.quarantineReason() == null
                ? QuarantineReason.RECONCILIATION_FINGERPRINT_CONFLICT
                : result.quarantineReason();
        if (!wal.quarantineKey(key, qr)) {
            throw new BankTransferBlockedException(
                    "对账隔离证据无法持久化（marker 写入失败），银行转账 fail closed", true);
        }
    }

    /** 登录对账写回（第八轮）：MR_BLOCK 也先持久化 marker；仅 USE_WAL 安全写回。 */
    public boolean reconcileWriteBack(UUID playerId, String requestId) {
        TransferKey key = TransferKey.of(playerId, requestId);
        if (recovery.globallyQuarantined() || recovery.quarantinedKeys().contains(key)) {
            return false;
        }
        BankTransferRecord walLatest = recovery.latest(key).orElse(null);
        BankTransferRecord attach = nested.find(playerId, requestId);
        Result result = decide(key, walLatest, attach);
        if (result.decision() == Decision.MANUAL_REVIEW_BLOCK) {
            persistReconciliationBlock(key, result);
            return false;
        }
        if (result.decision() == Decision.USE_WAL && result.record() != null) {
            return nested.write(playerId, result.record());
        }
        return false;
    }

    @Override
    public boolean write(UUID playerId, BankTransferRecord record) {
        return nested.write(playerId, record);
    }

    @Override
    public BankTransferRecord find(UUID playerId, String requestId) {
        TransferKey key = TransferKey.of(playerId, requestId);
        if (recovery.globallyQuarantined()) {
            throw new BankTransferBlockedException("WAL 全局隔离：银行转账已 fail closed", true);
        }
        if (recovery.quarantinedKeys().contains(key)) {
            throw new BankTransferBlockedException("该转账被 WAL 隔离（数据异常），需人工审计", false);
        }
        BankTransferRecord walLatest = recovery.latest(key).orElse(null);
        BankTransferRecord attach = nested.find(playerId, requestId);
        Result result = decide(key, walLatest, attach);
        switch (result.decision()) {
            case USE_WAL:
            case USE_ATTACHMENT_READONLY:
                return result.record();
            case MANUAL_REVIEW_BLOCK:
                // 先把冲突持久化为 quarantine marker，再返回 MANUAL_REVIEW 阻断。
                persistReconciliationBlock(key, result);
                return markManaged(key, result.record(), result.reason());
            case NEW_REQUEST:
            default:
                return null;
        }
    }

    @Override
    public java.util.List<BankTransferRecord> recent(UUID playerId) {
        return nested.recent(playerId);
    }

    private static BankTransferRecord markManaged(TransferKey key, BankTransferRecord src, String reason) {
        BankTransferRecord base = src == null
                ? new BankTransferRecord(key.requestId(),
                        BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                        BankTransferPhase.MANUAL_REVIEW, BankTransferStatus.MANUAL_REVIEW,
                        reason, 0, 0, 0, 0, "", "", "", "", "", 0, 0,
                        "", OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION,
                        BankTransferRecord.UNKNOWN_EPOCH)
                : src;
        return new BankTransferRecord(base.requestId(), base.direction(),
                BankTransferPhase.MANUAL_REVIEW, BankTransferStatus.MANUAL_REVIEW,
                reason + "（冲突/隔离）", base.requestedCopper(), base.requestedSecuritiesCents(),
                base.actualDebitCents(), base.copperAmount(),
                base.opBankDebit(), base.opBankCredit(), base.opSecuritiesDebit(),
                base.opSecuritiesCredit(), base.opRollback(),
                base.bankBalanceCopper(), base.securitiesBalanceCents(),
                base.providerId(), base.operationIdVersion(), base.stateMachineVersion(),
                base.runtimeEpoch());
    }
}
