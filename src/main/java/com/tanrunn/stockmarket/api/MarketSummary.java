package com.tanrunn.stockmarket.api;

/**
 * 股市账户摘要（只读；所有金额单位为分）。
 *
 * <p>由 {@link StockMarketApi#summary} 在服务端主线程生成，严格由
 * {@link AccountSnapshot} 字段推导：不重复读取内部账户服务。</p>
 */
public record MarketSummary(
        long cashCents,
        long totalValueCents,
        long dailyPnlCents,
        int holdingKinds,
        int openOrderCount) {
}
