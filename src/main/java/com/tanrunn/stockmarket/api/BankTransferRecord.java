package com.tanrunn.stockmarket.api;

/**
 * 银行 ⇄ 证券转账账本行（持久化到玩家附件，供审计、阶段恢复与幂等重放）。
 *
 * <p>阶段状态机（{@link BankTransferPhase}，v2）随动账过程<b>先写意图后动账</b>：
 * PREPARED → SOURCE_DEBITED → DESTINATION_CREDIT_PENDING → DESTINATION_CREDITED →
 * COMPLETED；目标失败走 COMPENSATION_PENDING → COMPENSATED / COMPENSATION_FAILED；
 * 证据不足 → MANUAL_REVIEW。</p>
 *
 * <p><b>恢复权威依据</b>：
 * <ul>
 *   <li>{@code providerId} / {@code operationIdVersion}（opId 算法版本）/
 *       {@code stateMachineVersion}（状态机版本）/ {@code runtimeEpoch}（创建该笔时的
 *       服务器运行周期，<b>仅审计用</b>——同 epoch 不能证明 LC 内存幂等账本仍包含某 opId）。</li>
 *   <li>恢复只使用<b>账本持久化的 operationId</b>，禁止现场重算替换。</li>
 *   <li><b>不自动调用 LC</b>：LC 内存幂等账本可能 LRU 淘汰（≤2048 条，同 epoch 内也可能）；
 *       仅允许证券侧持久幂等重试（DESTINATION_CREDIT_PENDING 目标为证券时重试
 *       opSecuritiesCredit；COMPENSATION_PENDING 补偿目标为证券时重试 opRollback）。</li>
 * </ul></p>
 *
 * <p>安全终态（{@link BankTransferPhase#isSafeTerminal()}）可只读重放、零资金调用。
 * 旧版（无 stateMachineVersion 等新字段）非安全终态一律 fail closed 为 MANUAL_REVIEW。</p>
 *
 * @param requestId               原始客户端 requestId（查重/审计用；也作防重墓碑键）
 * @param direction               方向
 * @param phase                   当前阶段
 * @param status                  最新/终态状态
 * @param message                 安全审计消息（不含无界输入）
 * @param requestedCopper         入金请求的铜币数量（出金为 0）
 * @param requestedSecuritiesCents 出金请求的证券金额（分）（入金为该笔 N*100）
 * @param actualDebitCents        证券实际扣款（分）（出金为 copper*100；入金为 N*100）
 * @param copperAmount            LC 铜币数量（入金=N；出金=ceil(请求/100)）
 * @param opBankDebit             银行扣款 opId（入金）
 * @param opBankCredit            银行入账 opId（出金）
 * @param opSecuritiesDebit       证券扣款 opId（出金）
 * @param opSecuritiesCredit      证券入账 opId（入金）
 * @param opRollback              补偿 opId（区分 rollback_bank / rollback_securities）
 * @param bankBalanceCopper       记录时银行余额（铜币）
 * @param securitiesBalanceCents  记录时证券现金（分）
 * @param providerId              创建时的桥 provider id
 * @param operationIdVersion      opId 算法版本（见 {@link OperationIds#VERSION}）
 * @param stateMachineVersion     状态机版本（见 {@link #STATE_MACHINE_VERSION}）
 * @param runtimeEpoch            创建记录的服务器运行周期（0 = 缺失/未知；仅审计）
 */
public record BankTransferRecord(
        String requestId,
        BankTransferRequest.Direction direction,
        BankTransferPhase phase,
        BankTransferStatus status,
        String message,
        long requestedCopper,
        long requestedSecuritiesCents,
        long actualDebitCents,
        long copperAmount,
        String opBankDebit,
        String opBankCredit,
        String opSecuritiesDebit,
        String opSecuritiesCredit,
        String opRollback,
        long bankBalanceCopper,
        long securitiesBalanceCents,
        String providerId,
        int operationIdVersion,
        int stateMachineVersion,
        long runtimeEpoch) {

    /** 状态机版本：v2 = write-ahead 阶段 + 不自动调 LC 恢复（第四轮）。 */
    public static final int STATE_MACHINE_VERSION = 2;

    /** runtimeEpoch 缺失/未知的哨兵值。 */
    public static final long UNKNOWN_EPOCH = 0L;

    public BankTransferRecord {
        requestId = requestId == null ? "" : requestId;
        message = message == null ? "" : message;
        providerId = providerId == null ? "" : providerId;
        opBankDebit = nz(opBankDebit);
        opBankCredit = nz(opBankCredit);
        opSecuritiesDebit = nz(opSecuritiesDebit);
        opSecuritiesCredit = nz(opSecuritiesCredit);
        opRollback = nz(opRollback);
        if (phase == null) {
            phase = BankTransferPhase.MANUAL_REVIEW;
        }
        if (status == null) {
            status = BankTransferStatus.MANUAL_REVIEW;
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** 是否安全终态（可只读重放、零资金调用）。 */
    public boolean isSafeTerminal() {
        return phase != null && phase.isSafeTerminal();
    }

    /** 防重指纹是否一致（同 requestId 同方向同金额 → 幂等重放；否则请求冲突）。 */
    public boolean dedupMatches(BankTransferRecord other) {
        if (other == null) {
            return false;
        }
        return requestId.equals(other.requestId())
                && direction == other.direction()
                && requestedCopper == other.requestedCopper()
                && requestedSecuritiesCents == other.requestedSecuritiesCents()
                && actualDebitCents == other.actualDebitCents()
                && copperAmount == other.copperAmount();
    }

    /**
     * 恢复身份是否一致（第八轮）：除防重指纹外，还严格比较可能触发自动证券恢复所依赖的
     * 全部字段——providerId、operationIdVersion、stateMachineVersion、runtimeEpoch、
     * 方向所需的全部 opId。任一不一致 → keyed quarantine / MANUAL_REVIEW，资金 0；
     * 绝不能选一侧 opId“覆盖”另一侧。旧版安全终态因缺字段一律不进入自动恢复（只读）。
     */
    public boolean recoveryIdentityMatches(BankTransferRecord other) {
        if (other == null || !dedupMatches(other)) {
            return false;
        }
        if (!nz(providerId).equals(nz(other.providerId()))
                || operationIdVersion != other.operationIdVersion()
                || stateMachineVersion != other.stateMachineVersion()
                || runtimeEpoch != other.runtimeEpoch()) {
            return false;
        }
        return nz(opBankDebit).equals(nz(other.opBankDebit))
                && nz(opBankCredit).equals(nz(other.opBankCredit))
                && nz(opSecuritiesDebit).equals(nz(other.opSecuritiesDebit))
                && nz(opSecuritiesCredit).equals(nz(other.opSecuritiesCredit))
                && nz(opRollback).equals(nz(other.opRollback));
    }
}
