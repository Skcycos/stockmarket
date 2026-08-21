package com.tanrunn.stockmarket.api;

/**
 * 银行 ⇄ 证券转账被 WAL 隔离阻断（第六轮）：WAL 文件整体不可读 / 存在无法归属的损坏行时
 * （{@code global=true}）所有银行转账 fail closed（UNAVAILABLE）；某个复合键被隔离时
 * （{@code global=false}）该键 MANUAL_REVIEW。两种情况都不调用 LC 或证券资金接口；
 * 普通证券交易不受影响。
 */
public final class BankTransferBlockedException extends RuntimeException {

    private final boolean global;

    public BankTransferBlockedException(String message, boolean global) {
        super(message);
        this.global = global;
    }

    public boolean global() {
        return global;
    }
}
