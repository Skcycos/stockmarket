package com.tanrunn.stockmarket.server.market;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性经济平衡模拟：用固定随机种子模拟价格路径（镜像 MarketService.tick 的
 * 步进：每游戏日 24000/100 = 240 次更新），统计每只股票的 30/90 游戏日中位
 * 价格倍率、最低/最高倍率、最大回撤与最低价格。
 *
 * <p>参数直接读取 data/stockmarket/stocks/*.json，保证与数据包保持同步。
 * 断言只使用宽松分位数（100 个种子下 p5/p95 有抽样误差），不做偶发失败的
 * 完全随机断言。
 */
class MarketSimulationTest {

    private static final int TICKS_PER_DAY = 24000 / 100; // Config.TICK_INTERVAL 默认 100
    private static final int SEEDS = 250;

    record StockParams(String id, String name, double initialPrice, double drift, double volatility) {
    }

    private static final List<String> STOCK_IDS = List.of("changg", "liuyun", "qingyun", "songzhu", "yanhuo", "zhujia");

    private static final Pattern FIELD = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*\"?([^,}\\r\\n]+)\"?");

    private static String field(String json, String key) {
        Matcher m = FIELD.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(key)) {
                return m.group(2).trim();
            }
        }
        throw new IllegalStateException("missing field " + key + " in " + json);
    }

    private static List<StockParams> loadDefinitions() throws IOException {
        List<StockParams> list = new ArrayList<>();
        for (String id : STOCK_IDS) {
            try (InputStream in = MarketSimulationTest.class.getClassLoader()
                    .getResourceAsStream("data/stockmarket/stocks/" + id + ".json")) {
                if (in == null) {
                    throw new IOException("missing stock json: " + id);
                }
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                list.add(new StockParams(id,
                        field(json, "name"),
                        Double.parseDouble(field(json, "initialPrice")),
                        Double.parseDouble(field(json, "drift")),
                        Double.parseDouble(field(json, "volatility"))));
            }
        }
        return list;
    }

    /** Mirrors MarketService.tick: one GBM step per market update, cents-rounded. */
    private static double[] simulate(StockParams p, long seed, int days) {
        Random random = new Random(seed);
        double price = p.initialPrice();
        double peak = price;
        double minPrice = price;
        double maxDrawdown = 0;
        int steps = days * TICKS_PER_DAY;
        for (int i = 0; i < steps; i++) {
            price = PriceModel.nextPrice(price, p.drift(), p.volatility(), random);
            if (price > peak) peak = price;
            if (price < minPrice) minPrice = price;
            double drawdown = (peak - price) / peak;
            if (drawdown > maxDrawdown) maxDrawdown = drawdown;
        }
        return new double[]{price / p.initialPrice(), maxDrawdown, peak / p.initialPrice(), minPrice};
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return (sorted[sorted.length / 2] + sorted[sorted.length / 2 - 1]) / 2.0;
    }

    private static double percentile(double[] values, double pct) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    @Test
    void priceModelStaysWithinBalancedBounds() throws IOException {
        List<StockParams> params = loadDefinitions();
        StringBuilder table = new StringBuilder();
        List<Result> results = new ArrayList<>();
        table.append(String.format("%n%-8s %-8s %8s %10s %10s %10s %10s %10s %10s %10s%n",
                "id", "name", "init", "drift", "vol", "med30", "med90", "minX", "maxX", "p5/95(30d)"));
        for (StockParams p : params) {
            double[] mult30 = new double[SEEDS];
            double[] mult90 = new double[SEEDS];
            double minPrice = Double.MAX_VALUE;
            double maxDrawdown30 = 0;
            double maxDrawdown90 = 0;
            for (int seed = 0; seed < SEEDS; seed++) {
                double[] r30 = simulate(p, seed, 30);
                double[] r90 = simulate(p, seed, 90);
                mult30[seed] = r30[0];
                mult90[seed] = r90[0];
                maxDrawdown30 = Math.max(maxDrawdown30, r30[1]);
                maxDrawdown90 = Math.max(maxDrawdown90, r90[1]);
                minPrice = Math.min(minPrice, Math.min(r30[3], r90[3]));
            }
            double med30 = median(mult30);
            double med90 = median(mult90);
            double minX = percentile(mult90, 0);
            double maxX = percentile(mult90, 100);
            double p5 = percentile(mult30, 5);
            double p95 = percentile(mult30, 95);
            table.append(String.format("%-8s %-8s %8.2f %10.6f %10.4f %10.3f %10.3f %10.3f %10.3f %8.2f/%.2f%n",
                    p.id(), p.name(), p.initialPrice(), p.drift(), p.volatility(),
                    med30, med90, minX, maxX, p5, p95));
            System.out.println(p.id() + ": 30d med=" + med30 + " 90d med=" + med90
                    + " minX=" + minX + " maxX=" + maxX + " maxDD30=" + String.format("%.3f", maxDrawdown30)
                    + " maxDD90=" + String.format("%.3f", maxDrawdown90) + " minPrice=" + minPrice);
            results.add(new Result(p.id(), med30, med90, p5, p95, minPrice));
        }
        System.out.println(table);
        // 硬性安全约束（先输出全部模拟数据，再统一断言）
        for (Result r : results) {
            assertTrue(r.minPrice >= 0.01, r.id + " price must never drop below 0.01, got " + r.minPrice);
            assertTrue(r.med30 > 0.85 && r.med30 < 1.18,
                    r.id + " 30d median multiplier must be near 1, got " + r.med30);
            assertTrue(r.med90 > 0.75 && r.med90 < 1.35,
                    r.id + " 90d median multiplier out of range, got " + r.med90);
            assertTrue(r.p5 >= 0.22, r.id + " 30d p5 too low: " + r.p5);
            assertTrue(r.p95 <= 3.5, r.id + " 30d p95 too high: " + r.p95);
        }
        System.out.println(table);

        // 风险差异：高波动股票的价格离散度必须明显大于低波动股票
        double low = riskSpread(params, "songzhu", 90);
        double high = riskSpread(params, "qingyun", 90);
        assertTrue(high > low * 1.5,
                "risk differentiation required: qingyun spread " + high + " vs songzhu " + low);
    }

    private static double riskSpread(List<StockParams> params, String id, int days) {
        StockParams p = params.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        double[] mult = new double[SEEDS];
        for (int seed = 0; seed < SEEDS; seed++) {
            mult[seed] = simulate(p, seed, days)[0];
        }
        return percentile(mult, 95) - percentile(mult, 5);
    }

    private record Result(String id, double med30, double med90, double p5, double p95, double minPrice) {
    }
}
