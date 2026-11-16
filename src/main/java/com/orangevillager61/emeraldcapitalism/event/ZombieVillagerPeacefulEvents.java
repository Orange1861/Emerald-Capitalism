package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;

/**
 * Optionally keeps zombie villagers alive on Peaceful difficulty.
 *
 * <p>Vanilla discards every hostile mob (including zombie villagers) during its despawn check on
 * Peaceful. Denying that check through the supported despawn event avoids a mixin. Zombie
 * villagers still obey normal despawn rules on other difficulties, and Peaceful damage scaling
 * still applies to their attacks.
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class ZombieVillagerPeacefulEvents {

    private ZombieVillagerPeacefulEvents() {
    }

    @SubscribeEvent
    public static void onMobDespawn(MobDespawnEvent event) {
        if (Config.zombieVillagersPersistInPeaceful
                && event.getEntity() instanceof ZombieVillager
                && event.getLevel().getDifficulty() == Difficulty.PEACEFUL) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }
}
