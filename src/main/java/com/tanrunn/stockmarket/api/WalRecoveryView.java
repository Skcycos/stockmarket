package com.tanrunn.stockmarket.api;

import java.util.Optional;
import java.util.Set;

/**
 * WAL 崩溃恢复视图（第六轮）：生产转账入口以此对账。
 * 实现（FileTransferWal）在加载/写入成功时维护这些只读索引。
 */
public interface WalRecoveryView {

    /** 某复合键的最新记录（不含被 quarantine 的键）。 */
    Optional<BankTransferRecord> latest(TransferKey key);

    /** 被隔离（数据异常/阶段倒退/校验失败）的复合键：命中即 MANUAL_REVIEW、零资金调用。 */
    Set<TransferKey> quarantinedKeys();

    /** WAL 全局隔离（文件不可读/无法归属的损坏）：所有银行转账 fail closed。 */
    boolean globallyQuarantined();

    /** 历史最大顺序号（下一条追加必须为其 +1，压缩不得重编号）。 */
    long lastSequence();
}
