package com.orangevillager61.emeraldcapitalism.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;

/** Prevents zombies from selecting a carrier as an attack target. */
public final class ZombieSmellEffect extends MobEffect {

    public ZombieSmellEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x6B4F35);
    }

    @Override
    public void onEffectAdded(LivingEntity entity, int amplifier) {
        if (entity.level().isClientSide()) {
            return;
        }

        entity.level().getEntitiesOfClass(
                        Zombie.class,
                        entity.getBoundingBox().inflate(64.0D),
                        zombie -> zombie.isAlive() && zombie.getTarget() == entity)
                .forEach(zombie -> {
                    zombie.setTarget(null);
                    zombie.getNavigation().stop();
                });
    }
}
