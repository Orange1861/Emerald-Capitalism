package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.util.LoadedChunkComposition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Lets farmers harvest pumpkins that are demonstrably part of a pumpkin plant.
 * Loose decorative pumpkins are never selected.
 */
public class HarvestPumpkinGoal extends Goal {

    private static final int SEARCH_RANGE = 16;
    private static final int VERTICAL_SEARCH_RANGE = 4;
    private static final int SEARCH_INTERVAL_TICKS = 40;
    private static final int EMPTY_SEARCH_INTERVAL_TICKS = 100;
    private static final double ARRIVAL_DISTANCE_SQ = 2.25;
    private static final float SPEED_MODIFIER = 0.6F;

    private final Villager villager;
    @Nullable
    private BlockPos targetPos;
    @Nullable
    private BlockPos navigationTarget;
    private long nextSearchTick;
    private final VillagerNavigationWatchdog navigationWatchdog = new VillagerNavigationWatchdog();
    private boolean failed;

    public HarvestPumpkinGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (villager.getVillagerData().getProfession() != VillagerProfession.FARMER
                || !(villager.level() instanceof ServerLevel level)
                || VillagerBreedingSessions.shouldYieldCustomWork(villager)
                || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                || level.getGameTime() < nextSearchTick
                || !canStorePumpkin()) {
            return false;
        }

        targetPos = PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.PUMPKIN_SEARCH,
                () -> findNearestAttachedPumpkin(level));
        nextSearchTick = level.getGameTime()
                + (targetPos == null ? EMPTY_SEARCH_INTERVAL_TICKS : SEARCH_INTERVAL_TICKS);
        return targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return targetPos != null
                && !failed
                && villager.level() instanceof ServerLevel level
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager)
                && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                && canStorePumpkin()
                && isAttachedPumpkin(level, targetPos);
    }

    @Override
    public void start() {
        navigationTarget = targetPos == null
                ? null
                : VillagerNavigationTargets.findReachableTarget(villager, targetPos, 2);
        failed = navigationTarget == null;
        navigationWatchdog.reset();
        setWalkTarget();
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || targetPos == null || navigationTarget == null) {
            return;
        }

        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(targetPos));
        if (villager.blockPosition().distSqr(targetPos) <= ARRIVAL_DISTANCE_SQ) {
            harvestPumpkin(level);
        } else if (navigationWatchdog.isStuck(villager, navigationTarget)) {
            failed = true;
            villager.getNavigation().stop();
        } else {
            setWalkTarget();
        }
    }

    @Override
    public void stop() {
        targetPos = null;
        navigationTarget = null;
        navigationWatchdog.reset();
        failed = false;
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    @Nullable
    private BlockPos findNearestAttachedPumpkin(ServerLevel level) {
        BlockPos origin = villager.blockPosition();
        LoadedChunkComposition composition = LoadedChunkComposition.find(
                level,
                origin.getX() - SEARCH_RANGE, origin.getX() + SEARCH_RANGE,
                origin.getY() - VERTICAL_SEARCH_RANGE, origin.getY() + VERTICAL_SEARCH_RANGE,
                origin.getZ() - SEARCH_RANGE, origin.getZ() + SEARCH_RANGE,
                state -> state.is(Blocks.PUMPKIN));
        if (composition.isEmpty()) {
            return null;
        }

        BlockPos nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;

        for (int x = -SEARCH_RANGE; x <= SEARCH_RANGE; x++) {
            for (int z = -SEARCH_RANGE; z <= SEARCH_RANGE; z++) {
                for (int y = -VERTICAL_SEARCH_RANGE; y <= VERTICAL_SEARCH_RANGE; y++) {
                    BlockPos candidate = origin.offset(x, y, z);
                    if (!composition.mayContain(candidate)
                            || !isAttachedPumpkin(composition, candidate)) {
                        continue;
                    }
                    double distanceSq = origin.distSqr(candidate);
                    if (distanceSq < nearestDistanceSq) {
                        nearest = candidate.immutable();
                        nearestDistanceSq = distanceSq;
                    }
                }
            }
        }
        return nearest;
    }

    private boolean isAttachedPumpkin(ServerLevel level, BlockPos pumpkinPos) {
        if (!level.hasChunk(pumpkinPos.getX() >> 4, pumpkinPos.getZ() >> 4)) {
            return false;
        }
        BlockState pumpkinState = level.getBlockState(pumpkinPos);
        if (!pumpkinState.is(Blocks.PUMPKIN)) {
            return false;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stemPos = pumpkinPos.relative(direction);
            if (!level.hasChunk(stemPos.getX() >> 4, stemPos.getZ() >> 4)) {
                continue;
            }
            BlockState stemState = level.getBlockState(stemPos);
            if (stemState.is(Blocks.ATTACHED_PUMPKIN_STEM)
                    && stemState.getValue(HorizontalDirectionalBlock.FACING) == direction.getOpposite()) {
                return true;
            }
        }
        return false;
    }

    private boolean isAttachedPumpkin(LoadedChunkComposition composition, BlockPos pumpkinPos) {
        BlockState pumpkinState = composition.getBlockStateIfLoaded(pumpkinPos);
        if (pumpkinState == null || !pumpkinState.is(Blocks.PUMPKIN)) {
            return false;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState stemState = composition.getBlockStateIfLoaded(pumpkinPos.relative(direction));
            if (stemState != null && stemState.is(Blocks.ATTACHED_PUMPKIN_STEM)
                    && stemState.getValue(HorizontalDirectionalBlock.FACING) == direction.getOpposite()) {
                return true;
            }
        }
        return false;
    }

    private boolean canStorePumpkin() {
        var inventory = villager.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || (stack.is(Items.PUMPKIN) && stack.getCount() < stack.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private void setWalkTarget() {
        if (targetPos != null && navigationTarget != null) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(navigationTarget, SPEED_MODIFIER, 1));
        }
    }

    private void harvestPumpkin(ServerLevel level) {
        if (targetPos == null || !isAttachedPumpkin(level, targetPos) || !canStorePumpkin()) {
            return;
        }

        if (level.destroyBlock(targetPos, false, villager)) {
            ItemStack remainder = villager.getInventory().addItem(new ItemStack(Items.PUMPKIN));
            if (!remainder.isEmpty()) {
                // The capacity check and mutation run on the server thread, so this should be unreachable.
                com.orangevillager61.emeraldcapitalism.util.EntityDropUtils.spawn(villager, level, remainder);
            }
        }
        targetPos = null;
    }
}
