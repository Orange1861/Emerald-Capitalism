package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.world.entity.ai.goal.RestrictSunGoal;
import net.minecraft.world.entity.monster.ZombieVillager;

/** Prevents zombie villagers from choosing sun-exposed destinations during the day. */
public final class ZombieVillagerSunAvoidanceGoal extends RestrictSunGoal {

    public ZombieVillagerSunAvoidanceGoal(ZombieVillager zombieVillager) {
        super(zombieVillager);
    }

    @Override
    public boolean canUse() {
        return Config.enableZombieVillagerSunAvoidance && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return Config.enableZombieVillagerSunAvoidance && super.canContinueToUse();
    }
}
