package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.ai.WanderingTraderAvoidBoatGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.WanderingTraderFenceGateGoal;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final Map<UUID, LadderPathCache> LADDER_PATH_CACHE = new HashMap<>();
    private static final Map<UUID, BlockPos> LADDER_EXIT_TARGETS = new HashMap<>();

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
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof WanderingTrader trader) {
            LADDER_DIRECTIONS.remove(trader.getUUID());
            LADDER_PATH_CACHE.remove(trader.getUUID());
            LADDER_EXIT_TARGETS.remove(trader.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LADDER_DIRECTIONS.clear();
        LADDER_PATH_CACHE.clear();
        LADDER_EXIT_TARGETS.clear();
    }

    private static void tickLadderTraversal(WanderingTrader trader) {
        if (!Config.enableLadderTraversal) {
            LADDER_DIRECTIONS.remove(trader.getUUID());
            LADDER_PATH_CACHE.remove(trader.getUUID());
            LADDER_EXIT_TARGETS.remove(trader.getUUID());
            return;
        }

        BlockPos position = trader.blockPosition();
        boolean onClimbable = trader.onClimbable()
                || trader.level().getBlockState(position).is(BlockTags.CLIMBABLE)
                || trader.level().getBlockState(position.below()).is(BlockTags.CLIMBABLE);
        BlockPos exitTarget = LADDER_EXIT_TARGETS.get(trader.getUUID());
        if (exitTarget != null) {
            trader.getNavigation().stop();
            trader.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            double horizontalDistanceSq = trader.distanceToSqr(
                    exitTarget.getX() + 0.5D, trader.getY(), exitTarget.getZ() + 0.5D);
            if (horizontalDistanceSq < 1.0D) {
                trader.setPos(exitTarget.getX() + 0.5D,
                        Math.max(trader.getY(), exitTarget.getY()), exitTarget.getZ() + 0.5D);
                LADDER_EXIT_TARGETS.remove(trader.getUUID());
                return;
            }
            double pullX = (exitTarget.getX() + 0.5D - trader.getX()) * 0.25D;
            double pullZ = (exitTarget.getZ() + 0.5D - trader.getZ()) * 0.25D;
            double upwardPull = exitTarget.getY() > trader.getY() + 0.1D ? 0.2D : 0.0D;
            trader.setDeltaMovement(pullX, upwardPull, pullZ);
            return;
        }
        if (!onClimbable) {
            LADDER_DIRECTIONS.remove(trader.getUUID());
            LADDER_PATH_CACHE.remove(trader.getUUID());
            LADDER_EXIT_TARGETS.remove(trader.getUUID());
            return;
        }

        int direction = LADDER_DIRECTIONS.getOrDefault(trader.getUUID(), 0);
        PathNavigation navigation = trader.getNavigation();
        Path path = navigation.getPath();
        if (path != null) {
            int nextNodeIndex = path.getNextNodeIndex();
            LadderPathCache cached = LADDER_PATH_CACHE.get(trader.getUUID());
            if (cached == null || cached.path != path || cached.nextNodeIndex != nextNodeIndex
                    || cached.positionX != position.getX()
                    || cached.positionY != position.getY()
                    || cached.positionZ != position.getZ()) {
                Node end = path.getEndNode();
                if (end.x == position.getX() && end.z == position.getZ()
                        && end.y != position.getY()) {
                    // The next-node cursor can point behind the trader after
                    // a same-column ladder node is marked reached. The path
                    // end remains stable and describes the intended vertical
                    // direction.
                    direction = end.y > position.getY() ? 1 : -1;
                    LADDER_DIRECTIONS.put(trader.getUUID(), direction);
                }
                LADDER_PATH_CACHE.put(trader.getUUID(),
                        new LadderPathCache(path, nextNodeIndex,
                                position.getX(), position.getY(), position.getZ(), direction));
            } else {
                direction = cached.direction;
            }
        } else {
            LADDER_PATH_CACHE.remove(trader.getUUID());
        }
        if (direction == 0) {
            return;
        }

        BlockPos ladderPosition = resolveNearbyLadder(trader, position);
        if (ladderPosition == null) {
            LADDER_DIRECTIONS.remove(trader.getUUID());
            LADDER_PATH_CACHE.remove(trader.getUUID());
            return;
        }

        boolean canContinue = direction > 0
                ? trader.level().getBlockState(ladderPosition.above()).is(BlockTags.CLIMBABLE)
                : trader.level().getBlockState(ladderPosition.below()).is(BlockTags.CLIMBABLE);
        if (!canContinue) {
            BlockState state = trader.level().getBlockState(ladderPosition);
            if (state.hasProperty(LadderBlock.FACING)) {
                Direction facing = state.getValue(LadderBlock.FACING);
                BlockPos exitPos = ladderPosition.relative(facing);
                if (isClearForTrader(trader, exitPos)) {
                    LADDER_EXIT_TARGETS.put(trader.getUUID(), exitPos);
                    LADDER_DIRECTIONS.put(trader.getUUID(), direction);
                    trader.setDeltaMovement(
                            (exitPos.getX() + 0.5D - trader.getX()) * 0.2D,
                            direction > 0 ? 0.08D : 0.0D,
                            (exitPos.getZ() + 0.5D - trader.getZ()) * 0.2D);
                    return;
                }
            }
            LADDER_DIRECTIONS.remove(trader.getUUID());
            LADDER_PATH_CACHE.remove(trader.getUUID());
            return;
        }
        double pullX = (position.getX() + 0.5D - trader.getX()) * 0.2D;
        double pullZ = (position.getZ() + 0.5D - trader.getZ()) * 0.2D;
        trader.setDeltaMovement(pullX, direction > 0 ? 0.2D : -0.15D, pullZ);
    }

    private static boolean isClearForTrader(WanderingTrader trader, BlockPos feetPos) {
        return trader.level().getBlockState(feetPos)
                        .getCollisionShape(trader.level(), feetPos).isEmpty()
                && trader.level().getBlockState(feetPos.above())
                        .getCollisionShape(trader.level(), feetPos.above()).isEmpty();
    }

    private static BlockPos resolveNearbyLadder(WanderingTrader trader, BlockPos position) {
        if (trader.level().getBlockState(position).is(BlockTags.CLIMBABLE)) {
            return position;
        }
        // BlockPos floors the entity's Y coordinate; check both neighboring
        // rungs so the top and bottom transitions cannot lose the column.
        if (trader.level().getBlockState(position.above()).is(BlockTags.CLIMBABLE)) {
            return position.above();
        }
        if (trader.level().getBlockState(position.below()).is(BlockTags.CLIMBABLE)) {
            return position.below();
        }
        for (int distance = 1; distance <= 2; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) {
                        continue;
                    }
                    BlockPos horizontal = position.offset(dx, 0, dz);
                    if (trader.level().getBlockState(horizontal).is(BlockTags.CLIMBABLE)) {
                        return horizontal;
                    }
                }
            }
        }
        return null;
    }

    private record LadderPathCache(Path path, int nextNodeIndex,
                                   int positionX, int positionY, int positionZ, int direction) {
    }
}
