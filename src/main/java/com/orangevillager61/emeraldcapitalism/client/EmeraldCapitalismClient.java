package com.orangevillager61.emeraldcapitalism.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.client.model.EmeraldGolemModel;
import com.orangevillager61.emeraldcapitalism.client.model.EmeraldSkrimisherModel;
import com.orangevillager61.emeraldcapitalism.client.renderer.EmeraldChestItemRenderer;
import com.orangevillager61.emeraldcapitalism.client.renderer.EmeraldChestRenderer;
import com.orangevillager61.emeraldcapitalism.client.renderer.EmeraldGreenBedItemRenderer;
import com.orangevillager61.emeraldcapitalism.client.renderer.EmeraldGreenBedRenderer;
import com.orangevillager61.emeraldcapitalism.client.renderer.EmeraldGolemRenderer;
import com.orangevillager61.emeraldcapitalism.client.renderer.EmeraldSkrimisherRenderer;
import com.orangevillager61.emeraldcapitalism.client.renderer.VillagePOIOverlayRenderer;
import com.orangevillager61.emeraldcapitalism.client.screen.BankScreen;
import com.orangevillager61.emeraldcapitalism.client.screen.EmeraldOreProcessorScreen;
import com.orangevillager61.emeraldcapitalism.client.screen.VillageManagerScreen;
import com.orangevillager61.emeraldcapitalism.client.screen.VillagePOIScreen;
import com.orangevillager61.emeraldcapitalism.client.screen.VillagerStatsScreen;
import com.orangevillager61.emeraldcapitalism.client.screen.SawmillScreen;
import com.orangevillager61.emeraldcapitalism.menu.ECAPMenuTypes;
import com.orangevillager61.emeraldcapitalism.network.RequestVillagePOIsPacket;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIClientCache;
import com.orangevillager61.emeraldcapitalism.network.MarketDataClientCache;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.registry.ECAPRecipeTypes;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.level.LevelEvent;

@Mod(value = EmeraldCapitalism.MODID, dist = Dist.CLIENT)
public class EmeraldCapitalismClient {

    private static final KeyMapping KEY_OPEN_POI_SCREEN = new KeyMapping(
            "key." + EmeraldCapitalism.MODID + ".toggle_poi_overlay",
            InputConstants.KEY_O,
            "key.categories." + EmeraldCapitalism.MODID
    );

    public static KeyMapping getOpenPoiScreenKeyMapping() {
        return KEY_OPEN_POI_SCREEN;
    }

    public EmeraldCapitalismClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(EmeraldCapitalismClient::registerRenderers);
        modEventBus.addListener(EmeraldCapitalismClient::registerLayerDefinitions);
        modEventBus.addListener(this::registerScreens);
        modEventBus.addListener(EmeraldCapitalismClient::registerKeyMappings);
        modEventBus.addListener(EmeraldCapitalismClient::registerClientExtensions);
        modEventBus.addListener(EmeraldCapitalismClient::registerRecipeBookCategories);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ECAPBlockEntityTypes.EMERALD_CHEST.get(), EmeraldChestRenderer::new);
        event.registerBlockEntityRenderer(ECAPBlockEntityTypes.EMERALD_GREEN_BED.get(), EmeraldGreenBedRenderer::new);
        event.registerEntityRenderer(ECAPEntityTypes.EMERALD_GOLEM.get(), EmeraldGolemRenderer::new);
        event.registerEntityRenderer(ECAPEntityTypes.EMERALD_SKRIMISHER.get(), EmeraldSkrimisherRenderer::new);
    }

    private static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(EmeraldGolemRenderer.EMERALD_GOLEM_LAYER, EmeraldGolemModel::createBodyLayer);
        event.registerLayerDefinition(EmeraldSkrimisherRenderer.EMERALD_SKRIMISHER_LAYER,
                EmeraldSkrimisherModel::createBodyLayer);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ECAPMenuTypes.VILLAGER_STATS_MENU.get(), VillagerStatsScreen::new);
        event.register(ECAPMenuTypes.VILLAGE_MANAGER_MENU.get(), VillageManagerScreen::new);
        event.register(ECAPMenuTypes.EMERALD_ORE_PROCESSOR_MENU.get(), EmeraldOreProcessorScreen::new);
        event.register(ECAPMenuTypes.BANK_MENU.get(), BankScreen::new);
        event.register(ECAPMenuTypes.SAWMILL_MENU.get(), SawmillScreen::new);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KEY_OPEN_POI_SCREEN);
    }

    private static void registerRecipeBookCategories(RegisterRecipeBookCategoriesEvent event) {
        event.registerRecipeCategoryFinder(ECAPRecipeTypes.SAWMILL.get(),
                recipe -> RecipeBookCategories.STONECUTTER);
    }

    private static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private final EmeraldChestItemRenderer renderer = new EmeraldChestItemRenderer(
                    Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                    Minecraft.getInstance().getEntityModels()
            );

            @Override
            public EmeraldChestItemRenderer getCustomRenderer() {
                return renderer;
            }
        }, ECAPItems.EMERALD_CHEST.get());

        event.registerItem(new IClientItemExtensions() {
            private final EmeraldGreenBedItemRenderer renderer = new EmeraldGreenBedItemRenderer(
                    Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                    Minecraft.getInstance().getEntityModels()
            );

            @Override
            public EmeraldGreenBedItemRenderer getCustomRenderer() {
                return renderer;
            }
        }, ECAPItems.EMERALD_GREEN_BED.get());
    }

    // Client event handlers

    @EventBusSubscriber(modid = EmeraldCapitalism.MODID, value = Dist.CLIENT)
    public static class ClientEvents {

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }

            while (KEY_OPEN_POI_SCREEN.consumeClick()) {
                PacketDistributor.sendToServer(RequestVillagePOIsPacket.nearest());
                mc.player.sendSystemMessage(Component.literal("[ECAP] Requesting nearest village POI data..."));
                mc.setScreen(new VillagePOIScreen());
            }
        }

        @SubscribeEvent
        public static void onClientLevelUnload(LevelEvent.Unload event) {
            if (event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel) {
                clearPoiClientState();
            }
        }

        @SubscribeEvent
        public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            clearPoiClientState();
        }

        @SubscribeEvent
        public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            clearPoiClientState();
        }

        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                return;
            }

            if (!VillagePOIOverlayRenderer.isEnabled()) {
                return;
            }

            Vec3 cameraPos = event.getCamera().getPosition();
            MultiBufferSource.BufferSource bufferSource = net.minecraft.client.Minecraft.getInstance()
                    .renderBuffers().bufferSource();

            VillagePOIOverlayRenderer.render(event.getPoseStack(), bufferSource, cameraPos);

            // Flush the line buffer so our lines actually appear
            bufferSource.endLastBatch();
        }

        private static void clearPoiClientState() {
            VillagePOIClientCache.clear();
            VillagePOIOverlayRenderer.clear();
            MarketDataClientCache.clear();
        }

    }
}
