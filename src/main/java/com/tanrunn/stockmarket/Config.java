package com.tanrunn.stockmarket;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for the stock market mod")
            .define("enabled", true);

    public static final ModConfigSpec.DoubleValue INITIAL_CASH = BUILDER
            .comment("Starting cash for every new player account")
            .defineInRange("initialCash", 1000.0, 0.0, 1_000_000_000.0);

    public static final ModConfigSpec.DoubleValue FEE_RATE = BUILDER
            .comment("Trading fee rate (fraction of the order value), e.g. 0.001 = 0.1%")
            .defineInRange("feeRate", 0.001, 0.0, 0.1);

    public static final ModConfigSpec.IntValue TICK_INTERVAL = BUILDER
            .comment("How often (in server ticks) the whole market updates its prices, e.g. 100 = every 5 seconds")
            .defineInRange("tickInterval", 100, 1, 1200);

    public static final ModConfigSpec.IntValue MAX_ORDER_QTY = BUILDER
            .comment("Maximum quantity allowed per order")
            .defineInRange("maxOrderQty", 9999, 1, 1_000_000);

    static final ModConfigSpec SPEC = BUILDER.build();
}
