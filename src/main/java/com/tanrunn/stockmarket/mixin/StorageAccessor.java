package com.tanrunn.stockmarket.mixin;

import com.sighs.apricityui.util.Storage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.LinkedHashMap;

/** Exposes ApricityUI's protected Storage map to the LocalStorage workaround. */
@Mixin(value = Storage.class, remap = false)
public interface StorageAccessor {
    @Accessor("data")
    LinkedHashMap<String, String> stockmarket$getData();
}
