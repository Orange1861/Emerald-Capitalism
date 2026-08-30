package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.ai.WanderingTraderAvoidBoatGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.WanderingTraderFenceGateGoal;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Wandering-trader-only lifecycle hooks. Regular villagers are intentionally excluded. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class WanderingTraderEvents {
    private static final Map<UUID, Integer> LADDER_DIRECTIONS = new HashMap<>();

    private WanderingTraderEvents() {
    }

    @SubscribeEvent
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        VillagerNameManager.assignWanderingTraderNameIfNeeded(trader);
        if (trader.goalSelector.getAvailableGoals().stream()
                .noneMatch(wrapped -> wrapped.getGoal() instanceof WanderingTraderAvoidBoatGoal)) {
            trader.goalSelector.addGoal(5, new WanderingTraderAvoidBoatGoal(trader));
        }
        if (trader.goalSelector.getAvailableGoals().stream()
                .noneMatch(wrapped -> wrapped.getGoal() instanceof WanderingTraderFenceGateGoal)) {
            trader.goalSelector.addGoal(6, new WanderingTraderFenceGateGoal(trader));
        }
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        if (trader.tickCount % 20 == 0) {
            VillagerNameManager.refreshWanderingTraderName(trader);
        }
        tickLadderTraversal(trader);
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof WanderingTrader trader) {
            LADDER_DIRECTIONS.remove(trader.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LADDER_DIRECTIONS.clear();
    }

    private static void tickLadderTraversal(WanderingTrader trader) {
        BlockPos position = trader.blockPosition();
        boolean onClimbable = trader.onClimbable()
                || trader.level().getBlockState(position).is(BlockTags.CLIMBABLE)
                || trader.level().getBlockState(position.below()).is(BlockTags.CLIMBABLE);
        if (!Config.enableLadderTraversal || !onClimbable) {
            LADDER_DIRECTIONS.remove(trader.getUUID());
            return;
        }

        int direction = LADDER_DIRECTIONS.getOrDefault(trader.getUUID(), 0);
        PathNavigation navigation = trader.getNavigation();
        Path path = navigation.getPath();
        if (path != null) {
            for (int index = Math.max(0, path.getNextNodeIndex() - 1);
                 index < path.getNodeCount(); index++) {
                Node node = path.getNode(index);
                if (node.x == position.getX() && node.z == position.getZ()
                        && node.y != position.getY()) {
                    direction = node.y > position.getY() ? 1 : -1;
                    LADDER_DIRECTIONS.put(trader.getUUID(), direction);
                    break;
                }
            }
        }
        if (direction == 0) {
            return;
        }

        boolean canContinue = direction > 0
                ? trader.level().getBlockState(position.above()).is(BlockTags.CLIMBABLE)
                : trader.level().getBlockState(position.below()).is(BlockTags.CLIMBABLE);
        if (!canContinue) {
            LADDER_DIRECTIONS.remove(trader.getUUID());
            return;
        }
        double pullX = (position.getX() + 0.5D - trader.getX()) * 0.2D;
        double pullZ = (position.getZ() + 0.5D - trader.getZ()) * 0.2D;
        trader.setDeltaMovement(pullX, direction > 0 ? 0.2D : -0.15D, pullZ);
    }
}
