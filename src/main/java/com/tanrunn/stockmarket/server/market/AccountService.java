package com.tanrunn.stockmarket.server.market;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * Player attachment entry point. Account data is stored on the player entity
 * and saved with the player dat.
 */
public final class AccountService {
    private AccountService() {
    }

    public static HoldingAccount get(net.minecraft.server.level.ServerPlayer player) {
        return data(player).toView();
    }

    public static void set(net.minecraft.server.level.ServerPlayer player, HoldingAccount account) {
        AccountData data = data(player);
        data.apply(account);
        player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
    }

    public static void reset(net.minecraft.server.level.ServerPlayer player) {
        AccountData data = data(player);
        data.initialized = true;
        data.cash = com.tanrunn.stockmarket.Config.INITIAL_CASH.get();
        data.holdings.clear();
        player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
    }

    private static AccountData data(net.minecraft.server.level.ServerPlayer player) {
        return player.getData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get());
    }
}
