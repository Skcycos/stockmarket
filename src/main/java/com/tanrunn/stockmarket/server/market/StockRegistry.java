package com.tanrunn.stockmarket.server.market;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tanrunn.stockmarket.StockMarketMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datapack-driven stock definitions: data/stockmarket/stocks/*.json
 * { "name": "...", "industry": "科技", "initialPrice": 10.0, "drift": 0.0002, "volatility": 0.02 }
 */
public final class StockRegistry {
    private static final Gson GSON = new Gson();

    private static volatile StockRegistry INSTANCE = new StockRegistry(defaultStocks());

    private final Map<String, Definition> stocks;

    private StockRegistry(Map<String, Definition> stocks) {
        this.stocks = Map.copyOf(stocks);
    }

    public record Definition(String id, String name, double initialPrice, double drift, double volatility,
                             String industry) {
        public Definition(String id, String name, double initialPrice, double drift, double volatility) {
            this(id, name, initialPrice, drift, volatility, "综合");
        }
    }

    public static StockRegistry get() {
        return INSTANCE;
    }

    public static void reload(ResourceManager manager) {
        INSTANCE = load(manager);
    }

    private static Map<String, Definition> defaultStocks() {
        Map<String, Definition> stocks = new LinkedHashMap<>();
        add(stocks, "yanhuo", "烟火食铺", 12.50, 0.0002, 0.020, "消费");
        add(stocks, "zhujia", "筑家建设", 8.20, 0.0001, 0.018, "建设");
        add(stocks, "changg", "长歌矿业", 23.60, 0.0003, 0.030, "资源");
        add(stocks, "liuyun", "流云商贸", 15.30, 0.0002, 0.024, "商贸");
        add(stocks, "qingyun", "青云科技", 45.80, 0.0005, 0.042, "科技");
        add(stocks, "songzhu", "松竹银行", 31.20, 0.0000, 0.010, "金融");
        return stocks;
    }

    private static void add(Map<String, Definition> stocks, String id, String name,
                            double price, double drift, double vol, String industry) {
        stocks.put(id, new Definition(id, name, price, drift, vol, industry));
    }

    private static StockRegistry load(ResourceManager manager) {
        Map<String, Definition> stocks = new LinkedHashMap<>(defaultStocks());
        for (Map.Entry<ResourceLocation, Resource> entry :
                manager.listResources("stocks", p -> p.getPath().endsWith(".json")).entrySet()) {
            String path = entry.getKey().getPath();
            String id = path.substring("stocks/".length(), path.length() - ".json".length());
            try (var in = entry.getValue().open(); var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement element = GSON.fromJson(reader, JsonElement.class);
                if (element != null && element.isJsonObject()) {
                    JsonObject obj = element.getAsJsonObject();
                    String name = obj.has("name") ? obj.get("name").getAsString() : id;
                    double price = obj.has("initialPrice") ? obj.get("initialPrice").getAsDouble() : 10.0;
                    double drift = obj.has("drift") ? obj.get("drift").getAsDouble() : 0.0;
                    double vol = obj.has("volatility") ? obj.get("volatility").getAsDouble() : 0.02;
                    String industry = obj.has("industry") ? obj.get("industry").getAsString() : "综合";
                    stocks.put(id, new Definition(id, name, price, drift, vol, industry));
                }
            } catch (Exception e) {
                StockMarketMod.LOGGER.error("Failed to load stock {} from datapack", id, e);
            }
        }
        return new StockRegistry(stocks);
    }

    public List<Definition> definitions() {
        return new ArrayList<>(stocks.values());
    }

    public Definition get(String id) {
        return stocks.get(id);
    }

    public boolean has(String id) {
        return stocks.containsKey(id);
    }

    public int size() {
        return stocks.size();
    }

    public static class ReloadListener extends SimplePreparableReloadListener<StockRegistry> {
        @Override
        protected StockRegistry prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
            return load(resourceManager);
        }

        @Override
        protected void apply(StockRegistry data, ResourceManager resourceManager, ProfilerFiller profiler) {
            INSTANCE = data;
        }
    }
}
