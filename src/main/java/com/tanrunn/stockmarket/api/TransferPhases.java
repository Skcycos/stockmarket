package com.tanrunn.stockmarket.api;

import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * v2 转账状态机的阶段偏序与合法转换图（第七轮：对账/压缩校验共用）。
 *
 * <p>分为正常完成链、补偿链、REJECTED、MANUAL_REVIEW 四条语义，边为状态机实际写入的
 * 单步转换（任何阶段都可进入 MANUAL_REVIEW，而 MANUAL_REVIEW / 各安全终态是吸收态，
 * 不可回退到其它阶段）。</p>
 *
 * <p>{@link #canProgressTo} 做 BFS 多步可达性：用于判定“一方是否合法领先另一方”，
 * 避免用单一比较规则把不同分支误判为同一条链。</p>
 */
public final class TransferPhases {

    private static final Map<BankTransferPhase, Set<BankTransferPhase>> EDGES = buildEdges();

    private TransferPhases() {
    }

    private static Map<BankTransferPhase, Set<BankTransferPhase>> buildEdges() {
        Map<BankTransferPhase, Set<BankTransferPhase>> edges = new LinkedHashMap<>();
        for (BankTransferPhase p : BankTransferPhase.values()) {
            edges.put(p, EnumSet.noneOf(BankTransferPhase.class));
        }
        // 正常完成链
        edges.get(BankTransferPhase.PREPARED).add(BankTransferPhase.SOURCE_DEBITED);
        edges.get(BankTransferPhase.SOURCE_DEBITED).add(BankTransferPhase.DESTINATION_CREDIT_PENDING);
        edges.get(BankTransferPhase.DESTINATION_CREDIT_PENDING).add(BankTransferPhase.DESTINATION_CREDITED);
        edges.get(BankTransferPhase.DESTINATION_CREDITED).add(BankTransferPhase.COMPLETED);
        // 补偿链（入金目标失败/银行扣款异常：PREPARED 也可能直接进入补偿意图）
        edges.get(BankTransferPhase.PREPARED).add(BankTransferPhase.COMPENSATION_PENDING);
        edges.get(BankTransferPhase.SOURCE_DEBITED).add(BankTransferPhase.COMPENSATION_PENDING);
        edges.get(BankTransferPhase.DESTINATION_CREDIT_PENDING).add(BankTransferPhase.COMPENSATION_PENDING);
        edges.get(BankTransferPhase.COMPENSATION_PENDING).add(BankTransferPhase.COMPENSATED);
        edges.get(BankTransferPhase.COMPENSATION_PENDING).add(BankTransferPhase.COMPENSATION_FAILED);
        edges.get(BankTransferPhase.PREPARED).add(BankTransferPhase.COMPENSATION_FAILED); // 银行扣款异常
        // REJECTED（明确未动账失败）
        edges.get(BankTransferPhase.PREPARED).add(BankTransferPhase.REJECTED);
        // MANUAL_REVIEW：任意阶段可进入；吸收态（不 forward）
        for (BankTransferPhase p : BankTransferPhase.values()) {
            if (p != BankTransferPhase.MANUAL_REVIEW) {
                edges.get(p).add(BankTransferPhase.MANUAL_REVIEW);
            }
        }
        return edges;
    }

    /** 单步合法转换（WAL 加载校验用）。 */
    public static boolean isDirectEdge(BankTransferPhase from, BankTransferPhase to) {
        if (from == null || to == null) {
            return false;
        }
        return from == to || EDGES.get(from).contains(to);
    }

    /**
     * 多步可达性：{@code from} 能否沿合法转换链到达 {@code to}（相等算可达）。
     * 用于判定“一方阶段是否明确领先另一方 / 是否同一条链”。
     */
    public static boolean canProgressTo(BankTransferPhase from, BankTransferPhase to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        Deque<BankTransferPhase> queue = new ArrayDeque<>();
        Set<BankTransferPhase> visited = EnumSet.noneOf(BankTransferPhase.class);
        queue.add(from);
        visited.add(from);
        while (!queue.isEmpty()) {
            BankTransferPhase cur = queue.poll();
            for (BankTransferPhase next : EDGES.get(cur)) {
                if (next == to) {
                    return true;
                }
                if (!visited.contains(next)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /** 两条阶段分支是否互相不可达（独立分支 → 不兼容）。 */
    public static boolean divergent(BankTransferPhase a, BankTransferPhase b) {
        return !canProgressTo(a, b) && !canProgressTo(b, a);
    }
}
