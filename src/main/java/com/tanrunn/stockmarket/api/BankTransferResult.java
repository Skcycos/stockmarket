package com.tanrunn.stockmarket.api;

/**
 * 银行 ⇄ 证券转账的服务端权威结果。
 *
 * <p>金额按「1 证券资金 = 1 铜币」规则：{@code copperAmount} 是 LC 铜币数，
 * {@code requestedSecuritiesCents} 是玩家请求的证券金额（分），{@code actualDebitCents}
 * 是向上取整后的实际证券扣款（分）。UI 二次确认与日志据此展示。</p>
 *
 * @param success                是否成功
 * @param status                 结果状态
 * @param message                固定安全文案（不含无界用户输入；成功/失败可含金额）
 * @param bankBalanceCopper       操作后银行余额（铜币）
 * @param securitiesBalanceCents  操作后证券账户现金（分）
 * @param requestedCopper         入金请求的铜币数量
 * @param requestedSecuritiesCents 出金请求的证券金额（分）
 * @param actualDebitCents        证券实际扣款（分）
 * @param copperAmount            LC 实际铜币数量
 * @param duplicate               是否为已处理请求的重放
 * @param requestId               原始请求幂等键
 */
public record BankTransferResult(
        boolean success,
        BankTransferStatus status,
        String message,
        long bankBalanceCopper,
        long securitiesBalanceCents,
        long requestedCopper,
        long requestedSecuritiesCents,
        long actualDebitCents,
        long copperAmount,
        boolean duplicate,
        String requestId) {

    public static BankTransferResult failure(BankTransferStatus status, String message,
                                             long bankBalanceCopper, long securitiesBalanceCents,
                                             long requestedCopper, long requestedSecuritiesCents,
                                             long actualDebitCents, long copperAmount, String requestId) {
        return new BankTransferResult(false, status, message, bankBalanceCopper,
                securitiesBalanceCents, requestedCopper, requestedSecuritiesCents,
                actualDebitCents, copperAmount, false, requestId);
    }

    /** 由完成的账本记录构造成功结果（重放时 duplicate=true）。 */
    public static BankTransferResult success(BankTransferRecord record, boolean duplicate) {
        return new BankTransferResult(true, BankTransferStatus.SUCCESS, record.message(),
                record.bankBalanceCopper(), record.securitiesBalanceCents(),
                record.requestedCopper(), record.requestedSecuritiesCents(),
                record.actualDebitCents(), record.copperAmount(), duplicate, record.requestId());
    }

    /** 由账本记录构造结果（成功位与状态取自记录；重放时 duplicate=true）。 */
    public static BankTransferResult stored(BankTransferRecord record, boolean duplicate) {
        boolean success = record.status() == BankTransferStatus.SUCCESS;
        return new BankTransferResult(success, record.status(), record.message(),
                record.bankBalanceCopper(), record.securitiesBalanceCents(),
                record.requestedCopper(), record.requestedSecuritiesCents(),
                record.actualDebitCents(), record.copperAmount(), duplicate, record.requestId());
    }
}
