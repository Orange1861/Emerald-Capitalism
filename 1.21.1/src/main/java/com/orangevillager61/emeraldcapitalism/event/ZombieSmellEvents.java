package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/** Server-side target suppression for carriers of the Zombie Smell effect. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class ZombieSmellEvents {

    private ZombieSmellEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof Zombie
                && event.getNewAboutToBeSetTarget() instanceof LivingEntity target
                && target.getEffect(ECAPEffects.ZOMBIE_SMELL) != null) {
            event.setCanceled(true);
        }
    }
}
