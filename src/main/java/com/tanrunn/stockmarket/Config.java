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

    public static final ModConfigSpec.IntValue MARKET_CYCLE_TICKS = BUILDER
            .comment("Length of one market day/cycle in server ticks; default 24000 matches one Minecraft day")
            .defineInRange("marketCycleTicks", 24000, 1200, 2_400_000);

    public static final ModConfigSpec.DoubleValue NEWS_EVENT_PROBABILITY = BUILDER
            .comment("Probability of generating one company news event at each market-cycle boundary")
            .defineInRange("newsEventProbability", 0.25, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue DIVIDEND_PROBABILITY = BUILDER
            .comment("Probability of a dividend event at each market-cycle boundary")
            .defineInRange("dividendProbability", 0.04, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue SPLIT_PROBABILITY = BUILDER
            .comment("Probability of a 2-for-1 stock split at each market-cycle boundary")
            .defineInRange("splitProbability", 0.01, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue HALT_PROBABILITY = BUILDER
            .comment("Probability of a temporary trading halt at each market-cycle boundary")
            .defineInRange("haltProbability", 0.015, 0.0, 1.0);

    public static final ModConfigSpec.IntValue HALT_DURATION_CYCLES = BUILDER
            .comment("Number of market cycles a temporary halt lasts")
            .defineInRange("haltDurationCycles", 1, 1, 30);

    public static final ModConfigSpec.DoubleValue DIVIDEND_PER_SHARE = BUILDER
            .comment("Dividend cash paid per share during a dividend event")
            .defineInRange("dividendPerShare", 0.05, 0.0, 1_000_000.0);

    public static final ModConfigSpec.DoubleValue INDEX_BASE_VALUE = BUILDER
            .comment("Base value for the equal-weight market index")
            .defineInRange("indexBaseValue", 1000.0, 1.0, 1_000_000_000.0);

    public static final ModConfigSpec.DoubleValue NEWS_IMPACT_MAX = BUILDER
            .comment("Maximum absolute news price impact")
            .defineInRange("newsImpactMax", 0.08, 0.0, 1.0);

    static final ModConfigSpec SPEC = BUILDER.build();
}
