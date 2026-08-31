package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.network.POIOverlaySubscriptions;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.menu.ECAPMenuTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class ClientBoundaryGameTests {

    private ClientBoundaryGameTests() {}

    @GameTest(template = "empty_3x3x3")
    public static void dedicatedServerInitializesGameplayRegistrationsWithoutClientCode(GameTestHelper helper) {
        if (BuiltInRegistries.ENTITY_TYPE.getKey(ECAPEntityTypes.EMERALD_GOLEM.get()) == null
                || BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(ECAPBlockEntityTypes.EMERALD_CHEST.get()) == null
                || BuiltInRegistries.ITEM.getKey(ECAPItems.EMERALD_GREEN_BED.get()) == null
                || BuiltInRegistries.MENU.getKey(ECAPMenuTypes.BANK_MENU.get()) == null) {
            helper.fail("A gameplay registration was unavailable during dedicated-server startup");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void poiOverlaySubscriptionIsRemovedWhenPlayerDisconnects(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        UUID villageId = UUID.randomUUID();

        POIOverlaySubscriptions.clearAll();
        POIOverlaySubscriptions.subscribe(playerId, villageId);
        if (!POIOverlaySubscriptions.isSubscribed(playerId)) {
            helper.fail("POI overlay subscription was not registered");
            return;
        }

        POIOverlaySubscriptions.onPlayerDisconnect(playerId);
        if (POIOverlaySubscriptions.isSubscribed(playerId)) {
            helper.fail("POI overlay subscription leaked after player disconnect");
            return;
        }
        helper.succeed();
    }
}
