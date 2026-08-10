package com.tanrunn.stockmarket;

import com.mojang.logging.LogUtils;
import com.tanrunn.stockmarket.network.ModPayloads;
import com.tanrunn.stockmarket.server.command.MarketCommand;
import com.tanrunn.stockmarket.server.hook.MarketHooks;
import com.tanrunn.stockmarket.server.market.AccountData;
import com.tanrunn.stockmarket.server.market.StockRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(StockMarketMod.MODID)
public class StockMarketMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "stockmarket";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);

    // Per-player stock account, stored with the player dat.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<AccountData>> ACCOUNT =
            ATTACHMENT_TYPES.<AttachmentType<AccountData>>register("account",
                    () -> AttachmentType.serializable(AccountData::new).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public StockMarketMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Network payloads (market snapshot / requests / trades).
        modEventBus.addListener(ModPayloads::register);

        ATTACHMENT_TYPES.register(modEventBus);

        // Register ourselves and the event hooks for server and other game events.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(MarketHooks.class);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("{} initialized", MODID);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MarketCommand.register(event.getServer().getCommands().getDispatcher());
    }

    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new StockRegistry.ReloadListener());
    }
}
