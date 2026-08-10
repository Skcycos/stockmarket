package com.tanrunn.stockmarket.common;

public record OrderInfo(
        long orderId,
        String stockId,
        boolean buy,
        double price,
        int quantity) {
}
