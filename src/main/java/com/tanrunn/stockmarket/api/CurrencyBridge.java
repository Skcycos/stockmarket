package com.tanrunn.stockmarket.api;

import java.util.UUID;

/**
 * Optional adapter implemented by an economy Mod (e.g. Lightman's Currency).
 *
 * <p><b>金额单位</b>：本桥的金额一律使用<b>LC 最小单位（= 铜币，1 铜币 =
 * {@code main} 链 1 core value）</b>。它只管自己的货币；与证券账户内部 cents 的换算由
 * {@link ExchangeRates} 集中完成（1 铜币 = 100 证券 cents 的内部存储换算，玩家可见
 * 规则为「1 证券资金 = 1 铜币」）。</p>
 *
 * <p>约定：
 * <ul>
 *   <li>只允许在服务端主线程调用；</li>
 *   <li>扣款/入账必须<b>精确</b>：发生部分扣款时必须内部全额补偿后返回
 *       PARTIAL_OPERATION（不得返回成功），补偿失败返回 COMPENSATION_FAILED 并输出
 *       critical/error 日志；</li>
 *   <li>{@code requestId} 是资金操作的<b>内部幂等键（opId）</b>：同 opId 同金额重放
 *       返回首次结果、不得重复扣款；同 opId 不同金额/方向返回 REQUEST_CONFLICT；
 *       调用方不得把客户端原始 requestId 直接传进来；</li>
 *   <li>failure 时不得抛异常，一律返回 {@link BridgeResult}（fail closed）。</li>
 * </ul></p>
 */
public interface CurrencyBridge {

    /** 桥接唯一 ID（如 server_menu:lc_bank_main）。 */
    String id();

    /** 展示名称（如 “LC 银行账户”）。 */
    String displayName();

    /** 是否当前可用（经济 Mod 已安装且就绪）。 */
    boolean isAvailable();

    /** 查询该玩家余额（LC 铜币）。 */
    long balanceCopper(UUID playerId);

    /**
     * 幂等、精确扣款（单位：LC 铜币）。
     *
     * @param copper     扣款铜币数量（&gt;0，精确）
     * @param requestId  内部操作幂等键（opId，见 {@link OperationIds}）
     */
    BridgeResult withdraw(UUID playerId, long copper, String source, String reason, String requestId);

    /** 幂等、精确入账（参数语义同 {@link #withdraw}）。 */
    BridgeResult deposit(UUID playerId, long copper, String source, String reason, String requestId);
}
