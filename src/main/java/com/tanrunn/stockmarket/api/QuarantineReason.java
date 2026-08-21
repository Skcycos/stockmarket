package com.tanrunn.stockmarket.api;

/**
 * WAL quarantine marker 的有界原因枚举（第八轮）：marker 是跨重启/跨压缩的持久隔离证据，
 * 只编码有界枚举与复合键、证据指纹哈希，绝不记录原始损坏行或其它无界输入。
 */
public enum QuarantineReason {
    /** 对账：WAL 与附件防重指纹（方向/金额）冲突。 */
    RECONCILIATION_FINGERPRINT_CONFLICT,
    /** 对账：可能触发自动证券恢复的恢复身份（provider/version/epoch/opId）不一致。 */
    RECONCILIATION_RECOVERY_IDENTITY_CONFLICT,
    /** 对账：附件阶段领先 WAL 且无法证明先后。 */
    RECONCILIATION_ATTACHMENT_AHEAD,
    /** 对账：WAL 与附件处于不同阶段分支。 */
    RECONCILIATION_DIVERGENT,
    /** 对账：任一侧为 MANUAL_REVIEW（保守优先）。 */
    RECONCILIATION_MANUAL_ATTACHMENT,
    /** 对账：WAL 缺失但附件非安全终态。 */
    RECONCILIATION_ATTACHMENT_NONSAFE,
    /** 对账：WAL 记录未通过 Validator。 */
    RECONCILIATION_WAL_ILLEGAL,
    /** 对账：附件记录未通过 Validator。 */
    RECONCILIATION_ATTACHMENT_ILLEGAL
}
