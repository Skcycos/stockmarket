package com.tanrunn.stockmarket.common;

import java.util.List;
import java.util.Map;

public record AccountInfo(
        double cash,
        double totalValue,
        double holdingsValue,
        double unrealizedPnl,
        double realizedPnl,
        double dailyPnl,
        double totalPnl,
        double reservedCash,
        double availableHoldingsValue,
        double reservedHoldingsValue,
        int availableHoldingsQuantity,
        int reservedHoldingsQuantity,
        Map<String, Integer> holdings,
        Map<String, Double> costBasis,
        List<OrderInfo> orders,
        List<TradeInfo> trades) {
}
