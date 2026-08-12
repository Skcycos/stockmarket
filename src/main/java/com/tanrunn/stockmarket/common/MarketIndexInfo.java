package com.tanrunn.stockmarket.common;

/** A lightweight market index view sent to clients and exposed to other Mods. */
public record MarketIndexInfo(String id, String name, double value, double changePct) {
}
