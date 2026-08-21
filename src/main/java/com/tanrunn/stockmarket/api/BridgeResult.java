package com.tanrunn.stockmarket.api;

/**
 * 银行桥接幂等操作结果。
 *
 * <p>{@code success == true} 时 {@code actualCopper} 必须等于请求金额（桥接内部保证
 * 足额、精确）。金额单位为 LC 最小单位（= 铜币）。非成功时 {@code status} 给出可审计
 * 原因；{@link BridgeStatusCode#PARTIAL_OPERATION} 表示桥接内部已部分扣款并全额补偿
 * （净额为零，调用方不得当作成功继续）。</p>
 *
 * @param success       是否成功且足额
 * @param actualCopper  实际处理金额（铜币）
 * @param status        状态码
 * @param message       固定安全文案（不包含无界用户输入）
 */
public record BridgeResult(boolean success, long actualCopper, BridgeStatusCode status, String message) {

    public static BridgeResult ok(long actualCopper) {
        return new BridgeResult(true, actualCopper, BridgeStatusCode.SUCCESS, "");
    }

    public static BridgeResult fail(BridgeStatusCode status, String message) {
        return new BridgeResult(false, 0, status, message);
    }

    public static BridgeResult fail(BridgeStatusCode status) {
        return fail(status, "");
    }
}
