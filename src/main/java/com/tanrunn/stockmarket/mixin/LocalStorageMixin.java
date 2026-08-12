package com.tanrunn.stockmarket.mixin;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.util.LocalStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ApricityUI 1.2.1 reflects the pre-1.21 NbtIo File overloads. NeoForge
 * 1.21.1 exposes Path/InputStream overloads instead, so the upstream
 * LocalStorage implementation logs an error and silently loses persistence.
 * Keep the workaround client-only because LocalStorage is a client service.
 */
@Mixin(value = LocalStorage.class, remap = false)
public abstract class LocalStorageMixin {
    /**
     * @author StockMarket
     * @reason Use the 1.21.1 NbtIo Path overload instead of the removed File overload.
     */
    @Overwrite
    public void save() {
        File storageFile = LocalStorage.getStorageFile();
        if (storageFile == null) return;
        try {
            File parentDir = storageFile.getParentFile();
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                ApricityUI.LOGGER.error("Failed to create config directory for LocalStorage: {}",
                        parentDir.getAbsolutePath());
                return;
            }
            CompoundTag tag = new CompoundTag();
            for (Map.Entry<String, String> entry : storageData().entrySet()) {
                tag.putString(entry.getKey(), entry.getValue());
            }
            NbtIo.writeCompressed(tag, storageFile.toPath());
        } catch (IOException | RuntimeException e) {
            ApricityUI.LOGGER.error("Failed to save LocalStorage data to {}",
                    storageFile.getAbsolutePath(), e);
        }
    }

    /**
     * @author StockMarket
     * @reason Use the 1.21.1 NbtIo Path/NbtAccounter overloads.
     */
    @Overwrite
    public void load() {
        File storageFile = LocalStorage.getStorageFile();
        if (storageFile == null || !storageFile.isFile()) return;
        try {
            CompoundTag tag = NbtIo.readCompressed(storageFile.toPath(), NbtAccounter.unlimitedHeap());
            storageData().clear();
            for (String key : tag.getAllKeys()) {
                storageData().put(key, tag.getString(key));
            }
        } catch (IOException | RuntimeException e) {
            ApricityUI.LOGGER.error("Failed to load LocalStorage from {}",
                    storageFile.getAbsolutePath(), e);
        }
    }

    private LinkedHashMap<String, String> storageData() {
        return ((StorageAccessor) (Object) this).stockmarket$getData();
    }
}
