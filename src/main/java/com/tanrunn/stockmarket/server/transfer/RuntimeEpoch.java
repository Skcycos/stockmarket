package com.tanrunn.stockmarket.server.transfer;

import com.tanrunn.stockmarket.api.BankTransferRecord;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 服务器运行周期标识（runtimeEpoch）。
 *
 * <p>每次服务端进程启动生成一个随机值并在<b>本运行周期内保持不变</b>。
 * 转账记录在 PREPARED 时保存当时的 epoch；SOURCE_DEBITED 恢复据此判断是否仍处于
 * 同一运行周期（此时 LC 内存幂等账本仍有效，可用持久化 opId 幂等恢复）。跨 epoch
 * 视为经历过重启：LC 侧没有持久幂等证据，出金不得自动向 LC 入账，一律 MANUAL_REVIEW；
 * 入金（目标证券）仅在证券 opId 持久幂等可靠时补证券入账，失败则 MANUAL_REVIEW。</p>
 */
public final class RuntimeEpoch {

    /** 与 {@link BankTransferRecord#UNKNOWN_EPOCH} 对齐的哨兵（0 = 缺失/未知）。 */
    private static volatile long value = BankTransferRecord.UNKNOWN_EPOCH;

    private RuntimeEpoch() {
    }

    /** 当前运行周期 epoch（非 0；首次调用惰性初始化）。 */
    public static long current() {
        long v = value;
        if (v == BankTransferRecord.UNKNOWN_EPOCH) {
            synchronized (RuntimeEpoch.class) {
                if (value == BankTransferRecord.UNKNOWN_EPOCH) {
                    long generated;
                    do {
                        generated = ThreadLocalRandom.current().nextLong();
                    } while (generated == 0);
                    value = generated;
                }
                v = value;
            }
        }
        return v;
    }

    /** 测试专用：重置（下次读取会再生一个新 epoch）。 */
    public static void resetForTesting() {
        value = BankTransferRecord.UNKNOWN_EPOCH;
    }
}
