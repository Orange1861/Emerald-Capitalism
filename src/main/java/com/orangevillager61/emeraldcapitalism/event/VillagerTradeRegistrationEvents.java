package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

import java.util.ArrayList;

/** Adds the mod's offers to the appropriate vanilla villager profession tier. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class VillagerTradeRegistrationEvents {

    private static final int JOURNEYMAN_LEVEL = 2;
    private static final int VILLAGE_MAP_EMERALD_COST = 8;
    private static final int VILLAGE_MAP_MAX_USES = 12;
    private static final int VILLAGE_MAP_VILLAGER_XP = 10;

    private VillagerTradeRegistrationEvents() {
    }

    @SubscribeEvent
    public static void addVillageMapTrade(VillagerTradesEvent event) {
        if (event.getType() != VillagerProfession.CARTOGRAPHER) {
            return;
        }

        event.getTrades()
                .computeIfAbsent(JOURNEYMAN_LEVEL, ignored -> new ArrayList<>())
                .add(new VillagerTrades.ItemsForEmeralds(
                        ECAPItems.VILLAGE_MAP.get(),
                        VILLAGE_MAP_EMERALD_COST,
                        1,
                        VILLAGE_MAP_MAX_USES,
                        VILLAGE_MAP_VILLAGER_XP));
    }
}
