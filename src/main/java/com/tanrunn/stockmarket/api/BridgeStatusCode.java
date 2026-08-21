package com.tanrunn.stockmarket.api;

/**
 * 银行桥接操作的状态码（对外适配器层）。
 *
 * <p>桥接实现（如 Server Menu 的 LC 适配器）负责把外部经济 Mod 的结果映射到本枚举；
 * 调用方（证券账户转账服务）据此区分重试、补偿与人工审计场景。</p>
 */
public enum BridgeStatusCode {
    /** 操作成功（实际金额 == 请求金额）。 */
    SUCCESS,
    /** 银行桥接不可用（经济 Mod 未安装 / 未就绪），fail closed。 */
    UNAVAILABLE,
    /** 不在服务端主线程调用。 */
    WRONG_THREAD,
    /** 玩家所在维度被隔离，交易被拒绝。 */
    QUARANTINED,
    /** 金额不合法。 */
    INVALID_AMOUNT,
    /** 银行余额不足。 */
    INSUFFICIENT_FUNDS,
    /** 金额无法精确转换（如货币链未加载）。 */
    CONVERSION_FAILED,
    /** 银行内部错误。 */
    PROVIDER_ERROR,
    /** 发生部分扣款；已由桥接内部全额补偿（净额为零，但不是成功）。 */
    PARTIAL_OPERATION,
    /** 部分扣款/中途失败后的补偿失败，需要人工审计。 */
    COMPENSATION_FAILED,
    /** requestId 不合法（长度/格式）。 */
    INVALID_REQUEST,
    /** 同一 requestId 已用于不同方向或不同金额，拒绝重放。 */
    REQUEST_CONFLICT,
    /** 余额运算溢出。 */
    AMOUNT_OVERFLOW,
}
