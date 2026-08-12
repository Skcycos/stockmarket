package com.tanrunn.stockmarket;

import com.sighs.apricityui.resource.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.io.IOException;
import java.io.InputStream;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = StockMarketMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = StockMarketMod.MODID, value = Dist.CLIENT)
public class StockMarketModClient {
    // AUI 页面中可用的内置粗体字体族名（思源黑体 Bold，子集化）
    public static final String UI_FONT = "stockmarket-ui";

    public StockMarketModClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // ApricityUI 硬依赖：客户端界面（行情面板、交易界面）在此接入
        event.enqueueWork(() -> registerUiFont());
        StockMarketMod.LOGGER.info("CLIENT SETUP");
    }

    private static void registerUiFont() {
        try (InputStream in = Minecraft.getInstance().getResourceManager()
                .open(ResourceLocation.fromNamespaceAndPath(StockMarketMod.MODID, "fonts/noto_sans_bold.otf"))) {
            if (Font.registerFont(UI_FONT, in)) {
                StockMarketMod.LOGGER.info("Registered built-in UI font: {}", UI_FONT);
            } else {
                StockMarketMod.LOGGER.warn("Failed to register built-in UI font: {}", UI_FONT);
            }
        } catch (IOException e) {
            StockMarketMod.LOGGER.error("Failed to load built-in UI font", e);
        }
    }
}
