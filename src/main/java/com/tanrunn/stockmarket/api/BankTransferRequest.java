package com.tanrunn.stockmarket.api;

/**
 * 银行 ⇄ 证券账户转账请求（纯业务值对象，可单测）。
 *
 * <p>兑换规则以「1 证券资金 = 1 铜币」为准：
 * <ul>
 *   <li><b>入金</b>：玩家输入整数铜币 N（{@link #requestedCopper}）；LC 扣 N 铜币，
 *       证券精确保增 N（内部 N*100 cents），服务端必须检查 ×100 溢出；</li>
 *   <li><b>出金</b>：玩家输入的证券金额（内部 {@link #requestedSecuritiesCents} 分）；
 *       服务端向上取整到整数铜币 {@code copper=ceil(请求/100)}，再按
 *       {@code copper*100} cents 实际扣证券（防止小数出金凭空增发铜币），ATM 到账
 *       {@code copper} 枚铜币。</li>
 * </ul>
 * 原始 requestId 只用于 StockMarket 自己的转账账本查重/审计；资金操作使用
 * {@link OperationIds} 生成的内部 opId，绝不直接外传原始 requestId。</p>
 *
 * @param direction               转账方向
 * @param requestedCopper         入金的铜币数量（DEPOSIT 时 &gt;0；WITHDRAW 时须为 0）
 * @param requestedSecuritiesCents 出金请求的证券金额（分）（WITHDRAW 时 &gt;0；DEPOSIT 时须为 0）
 * @param requestId               原始客户端幂等键（最长 {@value #MAX_REQUEST_ID_LENGTH}）
 */
public record BankTransferRequest(
        Direction direction,
        long requestedCopper,
        long requestedSecuritiesCents,
        String requestId) {

    public static final int MAX_REQUEST_ID_LENGTH = 64;

    public enum Direction {
        /** 银行 → 证券账户（LC 个人 ATM 账户入金）。 */
        DEPOSIT_TO_SECURITIES,
        /** 证券账户 → 银行（提现到 LC 个人 ATM 账户）。 */
        WITHDRAW_TO_BANK
    }

    public BankTransferRequest {
        requestId = requestId == null ? "" : requestId;
    }

    /**
     * 校验请求（方向相关金额与上限）。金额类问题返回 INVALID_AMOUNT，
     * 方向缺失/字段误用/requestId 问题返回 INVALID_REQUEST。
     *
     * @param maxCopper           入金铜币上限
     * @param maxSecuritiesCents  出金证券金额（分）上限
     * @return 对应失败状态，合法返回 null
     */
    public BankTransferStatus validateStatus(long maxCopper, long maxSecuritiesCents) {
        if (direction == null) {
            return BankTransferStatus.INVALID_REQUEST;
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            return BankTransferStatus.INVALID_REQUEST;
        }
        if (direction == Direction.DEPOSIT_TO_SECURITIES) {
            if (requestedCopper <= 0) {
                return BankTransferStatus.INVALID_AMOUNT;
            }
            if (requestedSecuritiesCents != 0) {
                return BankTransferStatus.INVALID_REQUEST;
            }
            if (maxCopper > 0 && requestedCopper > maxCopper) {
                return BankTransferStatus.INVALID_AMOUNT;
            }
        } else {
            if (requestedSecuritiesCents <= 0) {
                return BankTransferStatus.INVALID_AMOUNT;
            }
            if (requestedCopper != 0) {
                return BankTransferStatus.INVALID_REQUEST;
            }
            if (maxSecuritiesCents > 0 && requestedSecuritiesCents > maxSecuritiesCents) {
                return BankTransferStatus.INVALID_AMOUNT;
            }
        }
        return null;
    }

    /** 是否为“银行 → 证券”方向。 */
    public boolean isDepositToSecurities() {
        return direction == Direction.DEPOSIT_TO_SECURITIES;
    }
}
