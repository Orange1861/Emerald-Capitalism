package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.Config;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.level.pathfinder.Path;

/** Server-side checks shared by zombie-villager sun and target goals. */
final class ZombieVillagerSunSafety {

    private ZombieVillagerSunSafety() {
    }

    static boolean canAttackTarget(ZombieVillager zombieVillager, @Nullable LivingEntity target) {
        if (target == null) {
            return false;
        }
        if (!Config.enableZombieVillagerSunAvoidance) {
            return true;
        }
        if (!zombieVillager.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                || !zombieVillager.level().isDay()
                || zombieVillager.getLastHurtByMob() == target
                || zombieVillager.isWithinMeleeAttackRange(target)) {
            return true;
        }

        Path path = zombieVillager.getNavigation().createPath(target, 1);
        if (path == null || !path.canReach()) {
            return false;
        }

        for (int index = 0; index < path.getNodeCount(); index++) {
            BlockPos nodePosition = path.getNodePos(index);
            if (isBurningExposure(zombieVillager, nodePosition)) {
                return false;
            }
        }
        return true;
    }

    static boolean isBurningExposure(ZombieVillager zombieVillager, BlockPos position) {
        return zombieVillager.level().isDay()
                && zombieVillager.level().canSeeSky(position)
                && !zombieVillager.level().isRainingAt(position);
    }
}
