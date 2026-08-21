package com.tanrunn.stockmarket.api;

import java.util.UUID;

/**
 * 服务端资金意图的崩溃安全持久化接缝（第五轮）。
 *
 * <p>语义：在任何外部资金副作用之前，调用方必须把对应的“意图”阶段记录通过
 * {@link #writeIntent(UUID, BankTransferRecord)} <b>同步持久化</b>（追加 + flush +
 * {@link java.nio.channels.FileChannel#force(boolean)}），只有在返回 true 后才允许执行
 * 资金调用。返回 false 表示未落盘 → 调用方必须 fail closed（零资金调用）。</p>
 *
 * <p>注意：{@code AccountService.upsertTransfer} 里的 {@code player.setData(...)} 只更新
 * 玩家附件<b>内存</b>状态，不等同于同步磁盘写入；本接缝用于在内存附件之外再强制一条可
 * 恢复的资金意图。具体文件实现见 {@code server/transfer/FileTransferWal}。</p>
 *
 * <p>实现必须：
 * <ol>
 *   <li>只写服务器世界专用数据目录，不写工作区/全局目录；</li>
 *   <li>路径与内容不得包含未经处理的原始玩家输入（requestId 等需转义）；</li>
 *   <li>由服务端主线程串行调用（或提供明确锁策略）；</li>
 *   <li>写入失败在资金副作用之前 fail closed。</li>
 * </ol></p>
 */
public interface Wal {

    /**
     * 同步落盘一条资金意图（追加 + flush + force）。
     *
     * @param playerId 玩家 UUID
     * @param record   阶段意图记录
     * @return true = 已持久化成功；false = 未落盘，调用方必须先停止后续资金操作
     */
    boolean writeIntent(UUID playerId, BankTransferRecord record);

    /**
     * 同步落盘一条 keyed quarantine marker（追加 + flush + force，带校验和、seq、复合键）。
     * marker 是吸收态：一旦写入，该复合键在重启/压缩后仍被隔离，绝不解除。
     *
     * @return true = marker 已可靠持久化；false = 持久化失败（调用方必须 fail closed，
     *         不得依赖临时返回值/日志继续自动恢复）
     */
    default boolean quarantineKey(TransferKey key, QuarantineReason reason) {
        return false;
    }
}
