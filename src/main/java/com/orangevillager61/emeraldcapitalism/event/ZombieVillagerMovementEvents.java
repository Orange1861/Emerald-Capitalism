package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.entity.ai.ZombieVillagerFleeSunGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.ZombieVillagerSunAvoidanceGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.ZombieVillagerSunAwareTargetGoal;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Adds the configurable sun-avoidance movement rule to zombie villagers. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class ZombieVillagerMovementEvents {

    private ZombieVillagerMovementEvents() {
    }

    @SubscribeEvent
    public static void onZombieVillagerJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)
                || !(event.getEntity() instanceof ZombieVillager zombieVillager)) {
            return;
        }

        boolean hasSunAvoidanceGoal = zombieVillager.goalSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal() instanceof ZombieVillagerSunAvoidanceGoal);
        if (!hasSunAvoidanceGoal) {
            // Keep the navigation restriction above ordinary wandering and attack
            // movement. It only trims sun-exposed paths; target selection below
            // decides whether an attack is allowed to use such a path.
            zombieVillager.goalSelector.addGoal(1, new ZombieVillagerSunAvoidanceGoal(zombieVillager));
            zombieVillager.goalSelector.addGoal(1, new ZombieVillagerFleeSunGoal(zombieVillager, 1.0D));
        }

        boolean hasSunAwareTargetGoal = zombieVillager.targetSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal() instanceof ZombieVillagerSunAwareTargetGoal<?>);
        if (!hasSunAwareTargetGoal) {
            List<Goal> vanillaNearestTargetGoals = zombieVillager.targetSelector.getAvailableGoals().stream()
                    .map(goal -> goal.getGoal())
                    .filter(goal -> goal instanceof NearestAttackableTargetGoal<?>)
                    .toList();
            vanillaNearestTargetGoals.forEach(zombieVillager.targetSelector::removeGoal);

            zombieVillager.targetSelector.addGoal(2,
                    new ZombieVillagerSunAwareTargetGoal<>(zombieVillager, Player.class, true));
            zombieVillager.targetSelector.addGoal(3,
                    new ZombieVillagerSunAwareTargetGoal<>(zombieVillager, AbstractVillager.class, false));
            zombieVillager.targetSelector.addGoal(3,
                    new ZombieVillagerSunAwareTargetGoal<>(zombieVillager, IronGolem.class, true));
            zombieVillager.targetSelector.addGoal(5,
                    new ZombieVillagerSunAwareTargetGoal<>(zombieVillager, Turtle.class, 10, true, false,
                            Turtle.BABY_ON_LAND_SELECTOR));
        }
    }
}
