package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.entity.ai.ZombieDoorBreakingPolicy;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Assigns the configured ability once, after a newly spawned zombie has finalized spawning. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class ZombieDoorBreakingEvents {
    private ZombieDoorBreakingEvents() {
    }

    @SubscribeEvent
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Zombie zombie)
                || zombie.getSpawnType() == null) {
            return;
        }
        zombie.setCanBreakDoors(ZombieDoorBreakingPolicy.getsAbility(
                event.getLevel().getRandom().nextFloat(), Config.zombieDoorBreakingChancePercent));
    }
}
