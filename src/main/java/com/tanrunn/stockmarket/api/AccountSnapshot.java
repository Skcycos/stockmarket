package com.tanrunn.stockmarket.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable public account view. All money fields are integer cents. */
public record AccountSnapshot(
        UUID playerId,
        long cashCents,
        long totalValueCents,
        long holdingsValueCents,
        long unrealizedPnlCents,
        long realizedPnlCents,
        long dailyPnlCents,
        long totalPnlCents,
        long reservedCashCents,
        long availableHoldingsValueCents,
        long reservedHoldingsValueCents,
        int availableHoldingsQuantity,
        int reservedHoldingsQuantity,
        Map<String, Integer> holdings,
        Map<String, Long> costBasisCents,
        List<OrderSnapshot> orders,
        List<TradeSnapshot> trades,
        List<TransactionRecord> ledger) {

    public AccountSnapshot {
        holdings = Map.copyOf(holdings);
        costBasisCents = Map.copyOf(costBasisCents);
        orders = List.copyOf(orders);
        trades = List.copyOf(trades);
        ledger = List.copyOf(ledger);
    }
}
