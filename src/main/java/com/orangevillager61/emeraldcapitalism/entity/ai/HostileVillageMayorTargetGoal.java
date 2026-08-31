package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.world.village.VillageHostility;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

/** Makes unowned bank golems attack their village mayor during a contested election. */
public final class HostileVillageMayorTargetGoal extends NearestAttackableTargetGoal<Villager> {

    private final EmeraldGolem golem;

    public HostileVillageMayorTargetGoal(EmeraldGolem golem) {
        super(golem, Villager.class, 10, true, false,
                target -> target instanceof Villager mayor
                        && VillageHostility.isHostileMayor(golem, mayor));
        this.golem = golem;
    }

    @Override
    public boolean canUse() {
        if (golem.getRandom().nextInt(10) != 0) {
            return false;
        }

        Villager closestMayor = null;
        double closestDistance = Double.MAX_VALUE;
        for (Villager mayor : golem.level().getEntitiesOfClass(
                Villager.class,
                golem.getBoundingBox().inflate(this.getFollowDistance(), 4.0D, this.getFollowDistance()),
                mayor -> VillageHostility.isHostileMayor(golem, mayor))) {
            double distance = golem.distanceToSqr(mayor);
            if (distance < closestDistance) {
                closestMayor = mayor;
                closestDistance = distance;
            }
        }
        this.target = closestMayor;
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        @Nullable Villager target = golem.getTarget() instanceof Villager villager ? villager : null;
        return target != null
                && VillageHostility.isHostileMayor(golem, target)
                && golem.hasLineOfSight(target)
                && super.canContinueToUse();
    }
}
