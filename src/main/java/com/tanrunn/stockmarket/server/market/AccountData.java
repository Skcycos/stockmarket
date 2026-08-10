package com.tanrunn.stockmarket.server.market;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent per-player stock account, stored as a player attachment.
 */
public class AccountData implements INBTSerializable<CompoundTag> {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public boolean initialized = false;
    public double cash = 0;
    public Map<String, Integer> holdings = new HashMap<>();

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schemaVersion", schemaVersion);
        tag.putBoolean("initialized", initialized);
        tag.putDouble("cash", cash);
        ListTag list = new ListTag();
        holdings.forEach((id, qty) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id);
            entry.putInt("qty", qty);
            list.add(entry);
        });
        tag.put("holdings", list);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        schemaVersion = tag.getInt("schemaVersion");
        initialized = tag.getBoolean("initialized");
        cash = tag.getDouble("cash");
        holdings.clear();
        ListTag list = tag.getList("holdings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            holdings.put(entry.getString("id"), entry.getInt("qty"));
        }
    }

    public HoldingAccount toView() {
        return new HoldingAccount(cash, holdings);
    }

    public void apply(HoldingAccount account) {
        this.cash = account.cash();
        this.holdings = new HashMap<>(account.holdings());
    }
}
