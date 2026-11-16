package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Keeps each village's lumbermill furnace pool current as furnaces are placed and
 * broken. The village decides whether a furnace belongs in the pool; the periodic
 * reconciliation in the registry manager catches anything these events miss
 * (explosions, another mod, a cancelled break).
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class LumbermillFurnaceEvents {

    private LumbermillFurnaceEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFurnacePlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)
                || !event.getPlacedBlock().is(Blocks.FURNACE)) {
            return;
        }
        VillageRecord village = VillageRegistryData.get(level).getVillageFor(event.getPos());
        if (village != null) {
            village.acceptFurnaceSighting(level, event.getPos());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFurnaceBroken(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)
                || !event.getState().is(Blocks.FURNACE)) {
            return;
        }
        VillageRecord village = VillageRegistryData.get(level).getVillageFor(event.getPos());
        if (village != null) {
            village.onFurnaceRemoved(level, event.getPos());
        }
    }
}
