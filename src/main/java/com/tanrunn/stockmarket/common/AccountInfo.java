package com.tanrunn.stockmarket.common;

import java.util.List;
import java.util.Map;

public record AccountInfo(
        double cash,
        double totalValue,
        Map<String, Integer> holdings,
        List<OrderInfo> orders) {
}
