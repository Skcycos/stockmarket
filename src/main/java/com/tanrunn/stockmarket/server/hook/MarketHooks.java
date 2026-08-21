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
        // 第六轮：绑定当前世界路径并加载 WAL 恢复索引。
        com.tanrunn.stockmarket.server.transfer.BankTransferCoordinator.INSTANCE
                .onServerStarted(event.getServer());
        com.tanrunn.stockmarket.StockMarketMod.LOGGER.info("Stock market initialized");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MarketService service = MarketService.get();
        if (service != null) {
            service.save();
        }
        // 第六轮：关闭 WAL 资源、清空世界路径与冷却缓存（防重索引不删除）。
        com.tanrunn.stockmarket.server.transfer.BankTransferCoordinator.INSTANCE
                .onServerStopping();
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
        // 银行转账冷却记录：玩家退出即清除。
        com.tanrunn.stockmarket.server.transfer.BankTransferCoordinator.INSTANCE
                .onPlayerLoggedOut(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // 第六轮：玩家登录时用 WAL 最新记录对账（幂等；失败由 WAL overlay 阻止重放）。
        com.tanrunn.stockmarket.server.transfer.BankTransferCoordinator.INSTANCE
                .onPlayerLogin(player);
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
