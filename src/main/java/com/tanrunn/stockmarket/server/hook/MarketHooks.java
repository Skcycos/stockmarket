package com.tanrunn.stockmarket.server.hook;

import com.tanrunn.stockmarket.Config;
import com.tanrunn.stockmarket.server.market.AccountData;
import com.tanrunn.stockmarket.server.market.MarketService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public class MarketHooks {
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MarketService.init(event.getServer());
        com.tanrunn.stockmarket.StockMarketMod.LOGGER.info("Stock market initialized");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MarketService service = MarketService.get();
        if (service != null) {
            service.save();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Config.ENABLED.get()) return;
        MarketService service = MarketService.get();
        if (service != null) {
            service.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MarketService service = MarketService.get();
        if (service != null) {
            service.removeViewer(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AccountData data = player.getData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get());
        MarketService service = MarketService.get();
        if (!data.initialized) {
            data.initialized = true;
            data.cash = Config.INITIAL_CASH.get();
            if (service != null) data.lastCorporateActionId = service.latestCorporateActionId();
            player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
        }
        if (service != null) service.applyPendingCorporateActions(player);
    }
}
