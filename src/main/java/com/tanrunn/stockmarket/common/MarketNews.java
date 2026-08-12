package com.tanrunn.stockmarket.common;

/** Recent market/company event shown in the market panel. */
public record MarketNews(long id, long dayIndex, String stockId, String industry, String type,
                         String title, String detail, double impactPct) {
    public MarketNews {
        stockId = stockId == null ? "" : stockId;
        industry = industry == null ? "综合" : industry;
        type = type == null ? "NEWS" : type;
        title = title == null ? "市场消息" : title;
        detail = detail == null ? "" : detail;
    }
}
