package com.tanrunn.stockmarket.api;

/**
 * 银行 ⇄ 证券转账的服务端权威结果状态。
 */
public enum BankTransferStatus {
    /** 转账成功。 */
    SUCCESS,
    /** 银行桥接不可用（LC 未安装/未就绪），fail closed。 */
    UNAVAILABLE,
    /** 不在服务端主线程调用。 */
    WRONG_THREAD,
    /** 玩家所在维度被隔离。 */
    QUARANTINED,
    /** 金额非法。 */
    INVALID_AMOUNT,
    /** requestId 非法。 */
    INVALID_REQUEST,
    /** 来源余额不足（银行或证券侧；证券侧按向上取整后的实际扣款判断）。 */
    INSUFFICIENT_FUNDS,
    /** 银行侧错误（扣款/入账失败，未影响证券账户）。 */
    BANK_ERROR,
    /** 证券侧错误（入账/扣款失败）。 */
    SECURITIES_ERROR,
    /** 银行侧发生部分操作已内部补偿（净额为零）。 */
    PARTIAL_OPERATION,
    /** 主操作失败且补偿失败，需要人工审计。 */
    COMPENSATION_FAILED,
    /** 同一 requestId 已用于不同方向或不同金额。 */
    REQUEST_CONFLICT,
    /** 操作过于频繁被冷却拒绝。 */
    RATE_LIMITED,
    /** 转账停留在需要（幂等）恢复才能继续的状态（如 SOURCE_DEBITED），本次未动账。 */
    RECOVERY_REQUIRED,
    /** 转账未完成（通用非成功提示）。 */
    INCOMPLETE_TRANSFER,
    /** 证据不足/状态不确定，已标记人工审计，不得自动再次动账。 */
    MANUAL_REVIEW,
}
