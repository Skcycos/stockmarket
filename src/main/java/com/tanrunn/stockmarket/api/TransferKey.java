package com.tanrunn.stockmarket.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家级转账复合键（第六轮）：所有 WAL 防重索引 / 隔离 / 压缩 / 恢复 / 冲突判断
 * 一律使用 (playerUUID, requestId)，两个不同玩家允许使用相同 requestId 且完全隔离。
 */
public record TransferKey(UUID playerId, String requestId) {

    public TransferKey {
        Objects.requireNonNull(playerId, "playerId 不能为空");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
    }

    public static TransferKey of(UUID playerId, String requestId) {
        return new TransferKey(playerId, requestId);
    }
}
