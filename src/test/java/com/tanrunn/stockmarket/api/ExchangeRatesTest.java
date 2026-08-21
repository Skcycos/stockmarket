package com.tanrunn.stockmarket.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 兑换工具（1 证券资金 = 1 铜币；内部 1 铜币 = 100 证券 cents）测试含溢出。 */
class ExchangeRatesTest {

    @Test
    void copperToSecuritiesCentsIsExactMultiplier() {
        assertEquals(100L, ExchangeRates.copperToSecuritiesCents(1));
        assertEquals(1000L, ExchangeRates.copperToSecuritiesCents(10));
        assertEquals(10_000L, ExchangeRates.copperToSecuritiesCents(100));
        assertEquals(2_000_000L, ExchangeRates.copperToSecuritiesCents(20_000));
    }

    @Test
    void copperToSecuritiesCentsOverflowThrows() {
        assertThrows(ArithmeticException.class, () -> ExchangeRates.copperToSecuritiesCents(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> ExchangeRates.copperToSecuritiesCents(Long.MAX_VALUE / 100 + 1));
    }

    @Test
    void centsToCopperRoundsUpPerPlayerRule() {
        // 出金：向上取整到整数铜币
        assertEquals(1L, ExchangeRates.securitiesCentsToCopperCeil(1));   // 0.01 → 1
        assertEquals(1L, ExchangeRates.securitiesCentsToCopperCeil(100)); // 1.00 → 1
        assertEquals(2L, ExchangeRates.securitiesCentsToCopperCeil(101)); // 1.01 → 2
        assertEquals(2L, ExchangeRates.securitiesCentsToCopperCeil(150)); // 1.50 → 2
        assertEquals(2L, ExchangeRates.securitiesCentsToCopperCeil(200)); // 2.00 → 2
        assertEquals(3L, ExchangeRates.securitiesCentsToCopperCeil(201)); // 2.01 → 3
    }

    @Test
    void centsToCopperRejectsNonPositiveAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> ExchangeRates.securitiesCentsToCopperCeil(0));
        assertThrows(IllegalArgumentException.class, () -> ExchangeRates.securitiesCentsToCopperCeil(-1));
        assertThrows(ArithmeticException.class, () -> ExchangeRates.securitiesCentsToCopperCeil(Long.MAX_VALUE));
    }
}
