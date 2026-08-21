package com.tanrunn.stockmarket.api;

import java.util.List;
import java.util.UUID;

/**
 * 银行 ⇄ 证券转账账本存储抽象（阶段状态机持久化、幂等重放与审计）。
 *
 * <p>生产实现持久化在玩家附件（{@code AccountData}，有界，按 requestId upsert）；
 * 测试可注入纯内存实现。</p>
 */
public interface BankTransferLedger {

    /** 查找已处理的请求；未找到返回 null。 */
    BankTransferRecord find(UUID playerId, String requestId);

    /**
     * 写入/更新（按 requestId upsert）一笔转账的阶段记录。
     *
     * @return 是否落盘成功；false 表示容量已满且队尾为不可淘汰的在途记录——
     *         新转账的 PREPARED 阶段必须据此 fail closed（绝不动账）。
     */
    boolean write(UUID playerId, BankTransferRecord record);

    /** 最近转账（倒序）。 */
    List<BankTransferRecord> recent(UUID playerId);
}
