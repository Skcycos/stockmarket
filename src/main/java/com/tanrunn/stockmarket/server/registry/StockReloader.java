package com.tanrunn.stockmarket.server.registry;

import com.tanrunn.stockmarket.server.market.MarketService;
import com.tanrunn.stockmarket.server.market.StockRegistry;
import net.minecraft.server.MinecraftServer;

/** Re-reads the stock datapack and rebuilds the live market from persisted prices. */
public final class StockReloader {
    private StockReloader() {
    }

    public static void reload(MinecraftServer server) {
        StockRegistry.reload(server.getResourceManager());
        MarketService service = MarketService.get();
        if (service != null) {
            service.reloadStocks();
        }
    }
}
