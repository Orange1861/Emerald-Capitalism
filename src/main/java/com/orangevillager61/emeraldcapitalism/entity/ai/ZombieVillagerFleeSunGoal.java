package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FleeSunGoal;
import net.minecraft.world.entity.monster.ZombieVillager;

/** Moves a burning zombie villager to shade, while allowing safe attacks and retaliation. */
public final class ZombieVillagerFleeSunGoal extends FleeSunGoal {

    private final ZombieVillager zombieVillager;

    public ZombieVillagerFleeSunGoal(ZombieVillager zombieVillager, double speedModifier) {
        super(zombieVillager, speedModifier);
        this.zombieVillager = zombieVillager;
    }

    @Override
    public boolean canUse() {
        if (!Config.enableZombieVillagerSunAvoidance
                || !zombieVillager.isOnFire()
                || !zombieVillager.level().isDay()
                || !zombieVillager.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                || !zombieVillager.level().canSeeSky(zombieVillager.blockPosition())) {
            return false;
        }

        LivingEntity target = zombieVillager.getTarget();
        if (target != null && ZombieVillagerSunSafety.canAttackTarget(zombieVillager, target)) {
            return false;
        }
        return setWantedPos();
    }

    @Override
    public boolean canContinueToUse() {
        if (!Config.enableZombieVillagerSunAvoidance || zombieVillager.getNavigation().isDone()) {
            return false;
        }
        LivingEntity target = zombieVillager.getTarget();
        return target == null || !ZombieVillagerSunSafety.canAttackTarget(zombieVillager, target);
    }

    @Override
    public void start() {
        LivingEntity target = zombieVillager.getTarget();
        if (target != null && !ZombieVillagerSunSafety.canAttackTarget(zombieVillager, target)) {
            zombieVillager.setTarget(null);
        }
        super.start();
    }
}
