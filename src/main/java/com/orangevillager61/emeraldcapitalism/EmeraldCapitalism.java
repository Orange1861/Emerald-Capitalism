package com.orangevillager61.emeraldcapitalism;

import com.orangevillager61.emeraldcapitalism.command.VillageCommand;
import com.orangevillager61.emeraldcapitalism.command.VillageLocateCommand;
import com.orangevillager61.emeraldcapitalism.command.AbandonedVaultLocateCommand;
import com.orangevillager61.emeraldcapitalism.command.SteveGraveLocateCommand;
import com.orangevillager61.emeraldcapitalism.event.EmeraldGolemEvents;
import com.orangevillager61.emeraldcapitalism.event.SteveGraveEvents;
import com.orangevillager61.emeraldcapitalism.event.VillageDetectionHandler;
import com.orangevillager61.emeraldcapitalism.event.VillageRegistryEvents;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackSaplingCache;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackTreeReservations;
import com.orangevillager61.emeraldcapitalism.network.ECAPNetworking;
import com.orangevillager61.emeraldcapitalism.network.ManualVillageScanBudget;
import com.orangevillager61.emeraldcapitalism.network.POIOverlaySubscriptions;
import com.orangevillager61.emeraldcapitalism.network.DuplicateVillageBlocksPacket;
import com.orangevillager61.emeraldcapitalism.network.RequestExpandBoundsPacket;
import com.orangevillager61.emeraldcapitalism.network.RequestFullScanPacket;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIDataCache;
import com.orangevillager61.emeraldcapitalism.market.MarketRegistry;
import com.orangevillager61.emeraldcapitalism.registry.ECAPRegistries;
import com.orangevillager61.emeraldcapitalism.registry.ECAPPoiTypes;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameRefreshScheduler;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.util.SharedScanGenerationBudget;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootLexiconRegistry;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
//? if >=1.21.4 {
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
//?} else {
/*import net.neoforged.neoforge.event.AddReloadListenerEvent;
 *///?}
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(EmeraldCapitalism.MODID)
public class EmeraldCapitalism {
    public static final String MODID = "emeraldcapitalism";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EmeraldCapitalism(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);

        ECAPRegistries.registerAll(modEventBus);
//? if >=1.21.4 {
//?} else {
/*        modEventBus.addListener(ECAPPoiTypes::extendVanillaPoiTypes);
 *///?}

        EmeraldGolemEvents.register(modEventBus, NeoForge.EVENT_BUS);

        modEventBus.addListener(ECAPNetworking::onRegisterPayloadHandlers);

        modEventBus.addListener(Config::onLoad);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        clearServerSessionState();
    }

    @SubscribeEvent
//? if >=1.21.4 {
    public void onAddReloadListeners(AddServerReloadListenersEvent event) {
//?} else {
/*    public void onAddReloadListeners(AddReloadListenerEvent event) {
 *///?}
//? if >=1.21.4 {
        event.addListener(ModIds.id("resource_reload"), new SimplePreparableReloadListener<Void>() {
//?} else {
/*        event.addListener(new SimplePreparableReloadListener<Void>() {
 *///?}
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void ignored, ResourceManager resourceManager, ProfilerFiller profiler) {
                VillagerNameManager.loadNames(resourceManager);
                RootLexiconRegistry.load(resourceManager);
                MarketRegistry.load(resourceManager);
                LibraryBookRegistry.load(resourceManager);
                VillagerNameRefreshScheduler.requestAllLoadedRefresh();
            }
        });
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        clearServerSessionState();
    }

    private static void clearServerSessionState() {
        RequestFullScanPacket.clearCooldowns();
        RequestExpandBoundsPacket.clearCooldowns();
        DuplicateVillageBlocksPacket.clearCooldowns();
        ManualVillageScanBudget.clearAll();
        VillagePOIDataCache.clearAll();
        POIOverlaySubscriptions.clearAll();
        VillageRegistryEvents.clearManagers();
        VillagerBreedingSessions.clearAll();
        LumberjackTreeReservations.clearAll();
        LumberjackSaplingCache.clearAll();
        SharedScanGenerationBudget.clearAll();
        PerformanceTimingCounters.clear();
        VillageDetectionHandler.clearPendingWork();
        SteveGraveEvents.clearPendingWork();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        VillageCommand.register(event.getDispatcher());
        VillageLocateCommand.register(event.getDispatcher());
        AbandonedVaultLocateCommand.register(event.getDispatcher());
        SteveGraveLocateCommand.register(event.getDispatcher());
    }
}
