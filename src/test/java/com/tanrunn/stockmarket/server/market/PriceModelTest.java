package com.tanrunn.stockmarket.server.market;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceModelTest {

    @Test
    void deterministicWithSameSeed() {
        Random a = new Random(42L);
        Random b = new Random(42L);
        for (int i = 0; i < 50; i++) {
            assertEquals(PriceModel.nextPrice(10, 0, 0.02, a), PriceModel.nextPrice(10, 0, 0.02, b));
        }
    }

    @Test
    void staysPositiveAndRounded() {
        Random random = new Random(7L);
        for (int i = 0; i < 200; i++) {
            double price = PriceModel.nextPrice(10, 0, 0.02, random);
            assertTrue(price >= 0.01, "price must stay positive");
            assertEquals(price, Math.round(price * 100.0) / 100.0, "price rounded to cents");
        }
    }

    @Test
    void zeroVolatilityAndDriftKeepsPriceStable() {
        Random random = new Random(3L);
        double price = 10;
        for (int i = 0; i < 50; i++) {
            price = PriceModel.nextPrice(price, 0, 0, random);
        }
        assertEquals(10.0, price, 0.0001);
    }

    @Test
    void positiveDriftTrendsUp() {
        Random random = new Random(9L);
        double price = 10;
        for (int i = 0; i < 200; i++) {
            price = PriceModel.nextPrice(price, 0.01, 0, random);
        }
        assertTrue(price > 20, "2% drift over 200 steps should roughly double the price, got " + price);
    }
}
