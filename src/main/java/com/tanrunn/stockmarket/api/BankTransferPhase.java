package com.tanrunn.stockmarket.api;

/**
 * 银行 ⇄ 证券转账的持久化阶段（服务端权威状态机，v2）。
 *
 * <p>资金操作<b>先持久化阶段、后动账</b>（write-ahead）：任何外部资金副作用之前，
 * 先把“意图”落盘到玩家附件。阶段性：
 * <ul>
 *   <li>{@link #PREPARED}：请求已校验，动账前（或来源扣款结果未知）；</li>
 *   <li>{@link #SOURCE_DEBITED}：来源已扣款（LC 或证券）；</li>
 *   <li>{@link #DESTINATION_CREDIT_PENDING}：<b>即将</b>向目标入账（先写意图再调目标）；</li>
 *   <li>{@link #DESTINATION_CREDITED}：目标已入账；</li>
 *   <li>{@link #COMPENSATION_PENDING}：<b>即将</b>执行补偿（先写意图再调补偿）；</li>
 *   <li>{@link #COMPENSATED}：目标失败且已全额补偿（净额为零，非成功）；</li>
 *   <li>{@link #COMPENSATION_FAILED}：目标失败且补偿失败，需人工审计；</li>
 *   <li>{@link #MANUAL_REVIEW}：证据不足/状态不确定，需人工审计；</li>
 *   <li>{@link #COMPLETED}：整笔转账完成。</li>
 * </ul></p>
 *
 * <p>恢复语义（v2）：<b>恢复一律不自动调用 LC</b>（LC 内存幂等账本可能已 LRU 淘汰，
 * 同 runtimeEpoch 也不能证明其仍存在）。仅允许：目标为证券且处于
 * DESTINATION_CREDIT_PENDING（用账本内 opSecuritiesCredit 持久幂等重试）、或补偿目标
 * 为证券且处于 COMPENSATION_PENDING（用账本内 opRollback 持久幂等重试）。目标或补偿为
 * LC、旧版 SOURCE_DEBITED、字段畸形 → MANUAL_REVIEW，零资金调用。</p>
 */
public enum BankTransferPhase {
    PREPARED,
    SOURCE_DEBITED,
    DESTINATION_CREDIT_PENDING,
    DESTINATION_CREDITED,
    COMPENSATION_PENDING,
    COMPENSATED,
    COMPENSATION_FAILED,
    MANUAL_REVIEW,
    /** 明确的“无净资金变化”安全拒绝终态（第五轮）。 */
    REJECTED,
    COMPLETED;

    /** 是否为“不得再自动动账”的人工审计终态。 */
    public boolean isManualOnly() {
        return this == COMPENSATION_FAILED || this == MANUAL_REVIEW;
    }

    /** 是否为可只读重放、零资金调用的安全终态（含 REJECTED 与“已补偿但退款已付”等）。 */
    public boolean isSafeTerminal() {
        return this == COMPLETED
                || this == COMPENSATED
                || this == COMPENSATION_FAILED
                || this == MANUAL_REVIEW
                || this == REJECTED;
    }
}
