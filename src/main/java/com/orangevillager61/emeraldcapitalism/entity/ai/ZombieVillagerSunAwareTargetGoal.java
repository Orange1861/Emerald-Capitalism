package com.orangevillager61.emeraldcapitalism.entity.ai;

import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
//? if >=1.21.4 {
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
//?}
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.ZombieVillager;

/** Keeps vanilla nearest-target behavior while rejecting routes that expose the zombie villager to sun. */
public final class ZombieVillagerSunAwareTargetGoal<T extends LivingEntity>
        extends NearestAttackableTargetGoal<T> {

    private final ZombieVillager zombieVillager;
    private long lastSafetyCheck = Long.MIN_VALUE;
    @Nullable
    private LivingEntity cachedTarget;
    private boolean cachedSafety;

    public ZombieVillagerSunAwareTargetGoal(
            ZombieVillager zombieVillager,
            Class<T> targetType,
            boolean mustSee) {
//? if >=1.21.4 {
        this(zombieVillager, targetType, 10, mustSee, false, (target, level) -> true);
//?} else {
/*        this(zombieVillager, targetType, 10, mustSee, false, target -> true);
 *///?}
    }

    public ZombieVillagerSunAwareTargetGoal(
            ZombieVillager zombieVillager,
            Class<T> targetType,
            int randomInterval,
            boolean mustSee,
            boolean mustReach,
//? if >=1.21.4 {
            TargetingConditions.Selector targetSelector) {
        super(zombieVillager, targetType, randomInterval, mustSee, mustReach,
                (target, level) -> targetSelector.test(target, level)
                        && ZombieVillagerSunSafety.canAttackTarget(zombieVillager, target));
//?} else {
/*            Predicate<LivingEntity> targetSelector) {
        super(zombieVillager, targetType, randomInterval, mustSee, mustReach,
                targetSelector.and(target -> ZombieVillagerSunSafety.canAttackTarget(zombieVillager, target)));
 *///?}
        this.zombieVillager = zombieVillager;
    }

    @Override
    public boolean canContinueToUse() {
        return super.canContinueToUse() && isCachedTargetSafe(zombieVillager.getTarget());
    }

    private boolean isCachedTargetSafe(@Nullable LivingEntity target) {
        long gameTime = zombieVillager.level().getGameTime();
        if (target != cachedTarget || gameTime - lastSafetyCheck >= 10L) {
            cachedTarget = target;
            lastSafetyCheck = gameTime;
            cachedSafety = ZombieVillagerSunSafety.canAttackTarget(zombieVillager, target);
        }
        return cachedSafety;
    }
}
