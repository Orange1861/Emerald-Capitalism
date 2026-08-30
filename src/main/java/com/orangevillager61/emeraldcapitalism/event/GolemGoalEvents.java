package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldGolemInteractWithOpenablesGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.HostileVillagePlayerTargetGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.IronGolemInteractWithEmeraldDoorsGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.VaultGolemGoals;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.IronGolem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class GolemGoalEvents {
    private GolemGoalEvents() {
    }

    @SubscribeEvent
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof IronGolem golem)) {
            return;
        }

        if (golem.getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanOpenDoors(true);
            navigation.setCanPassDoors(true);
        }

        if (golem instanceof EmeraldGolem emeraldGolem) {
            addGoalIfAbsent(golem, EmeraldGolemInteractWithOpenablesGoal.class,
                    3, new EmeraldGolemInteractWithOpenablesGoal(emeraldGolem));
        } else {
            addGoalIfAbsent(golem, IronGolemInteractWithEmeraldDoorsGoal.class,
                    3, new IronGolemInteractWithEmeraldDoorsGoal(golem));
        }
        if (golem.targetSelector.getAvailableGoals().stream()
                .noneMatch(wrapped -> wrapped.getGoal() instanceof HostileVillagePlayerTargetGoal)) {
            golem.targetSelector.addGoal(1, new HostileVillagePlayerTargetGoal(golem));
        }
        VaultGolemGoals.suppressWandering(golem);
    }

    private static void addGoalIfAbsent(IronGolem golem, Class<? extends Goal> type,
                                        int priority, Goal goal) {
        if (golem.goalSelector.getAvailableGoals().stream()
                .noneMatch(wrapped -> type.isInstance(wrapped.getGoal()))) {
            golem.goalSelector.addGoal(priority, goal);
        }
    }
}
