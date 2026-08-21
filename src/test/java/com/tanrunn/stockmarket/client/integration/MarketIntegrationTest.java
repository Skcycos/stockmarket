package com.tanrunn.stockmarket.client.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketIntegrationTest {
    @Test
    void nullClassAttributeCanBeActivatedWithoutClientException() {
        assertEquals("active", MarketIntegration.activeClassValue(null, true));
        assertEquals("", MarketIntegration.activeClassValue(null, false));
    }

    @Test
    void activeClassIsReplacedWithoutDuplicates() {
        assertEquals("tab active", MarketIntegration.activeClassValue("tab active", true));
        assertEquals("tab", MarketIntegration.activeClassValue("tab active", false));
    }
}
