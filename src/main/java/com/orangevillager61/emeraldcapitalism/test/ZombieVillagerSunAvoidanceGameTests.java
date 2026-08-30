package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.entity.ai.ZombieVillagerSunAvoidanceGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.ZombieVillagerFleeSunGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.ZombieVillagerSunAwareTargetGoal;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class ZombieVillagerSunAvoidanceGameTests {

    private ZombieVillagerSunAvoidanceGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void zombieVillagersReceiveSunAvoidanceGoals(GameTestHelper helper) {
        ZombieVillager zombieVillager = helper.spawn(EntityType.ZOMBIE_VILLAGER, 1, 1, 1);

        long sunAvoidanceGoals = zombieVillager.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof ZombieVillagerSunAvoidanceGoal)
                .count();
        helper.assertValueEqual(sunAvoidanceGoals, 1L,
                "zombie villagers must receive exactly one sun-avoidance goal");
        long fleeSunGoals = zombieVillager.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof ZombieVillagerFleeSunGoal)
                .count();
        helper.assertValueEqual(fleeSunGoals, 1L,
                "zombie villagers must receive exactly one flee-sun goal");
        long sunAwareTargetGoals = zombieVillager.targetSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof ZombieVillagerSunAwareTargetGoal<?>)
                .count();
        helper.assertValueEqual(sunAwareTargetGoals, 4L,
                "zombie villagers must use sun-aware replacements for all nearest-target goals");
        helper.succeed();
    }
}
