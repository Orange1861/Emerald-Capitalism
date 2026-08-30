package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Goal that makes farmer villagers pathfind to trampled or degraded farmland
 * positions in the repair queue and restore them.
 *
 * <p>Uses the brain's {@link MemoryModuleType#WALK_TARGET} memory for movement
 * instead of direct navigation, so it cooperates with the villager's
 * brain-driven AI rather than conflicting with it.</p>
 */
public class ReplenishFarmlandGoal extends Goal {

    private static final double MAX_RANGE = 32.0;
    private static final double ARRIVAL_DISTANCE_SQ = 2.25; // 1.5 blocks
    private static final float SPEED_MODIFIER = 0.6F;
    /** Close-enough distance for the brain's WalkTarget (in blocks). */
    private static final int WALK_TARGET_CLOSE_ENOUGH = 1;
    private static final int EMPTY_QUEUE_RETRY_TICKS = 100;

    private final Villager villager;
    @Nullable
    private BlockPos targetPos;
    @Nullable
    private BlockPos navigationTarget;
    @Nullable
    private VillageRecord village;
    private final VillagerNavigationWatchdog navigationWatchdog = new VillagerNavigationWatchdog();
    private boolean failed;
    private long nextQueueLookupTick;

    public ReplenishFarmlandGoal(Villager villager) {
        this.villager = villager;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!Config.enableFarmlandRepair || VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.FARMER) {
            return false;
        }
        if (!(villager.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (serverLevel.getGameTime() < nextQueueLookupTick) {
            return false;
        }

        VillageRegistryData data = VillageRegistryData.get(serverLevel);
        VillageRecord found = data.getVillageFor(villager.blockPosition());
        if (found == null) {
            found = data.getNearestVillage(villager.blockPosition());
        }
        if (found == null) {
            nextQueueLookupTick = serverLevel.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return false;
        }
        if (!found.isFarmlandRepairEnabled()) {
            nextQueueLookupTick = serverLevel.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return false;
        }

        BlockPos nearest = found.getNearestUnclaimedRepair(villager.blockPosition(), MAX_RANGE);
        if (nearest == null) {
            nextQueueLookupTick = serverLevel.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return false;
        }

        this.village = found;
        this.targetPos = nearest;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetPos == null || village == null || VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return false;
        }
        // Continue only while this goal still owns a valid queued target.
        //
        // Why include claimedPositions?
        // - canUse() selects an unclaimed target, but another farmer can still race us
        //   before start() claims it.
        // - if claims are lost/reset while running, this goal should stop immediately
        //   and allow normal arbitration to pick a fresh target next tick.
        return !failed
                && village.getRepairQueue().contains(targetPos)
                && village.getClaimedPositions().contains(targetPos);
    }

    @Override
    public void start() {
        if (village != null && targetPos != null) {
            // If claiming fails, another farmer took this position between canUse() and start().
            // Abort this run so goal arbitration can pick a fresh target next cycle.
            if (!village.claimPosition(targetPos)) {
                targetPos = null;
                village = null;
                return;
            }
            navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, targetPos, 2);
            if (navigationTarget == null) {
                village.unclaimPosition(targetPos);
                targetPos = null;
                village = null;
                failed = true;
                return;
            }
            failed = false;
            navigationWatchdog.reset();
            setWalkTarget();
        }
    }

    @Override
    public void tick() {
        if (targetPos == null || village == null || navigationTarget == null) {
            return;
        }

        // Look at target
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                new BlockPosTracker(targetPos));

        // Check if arrived
        double distSq = villager.blockPosition().distSqr(targetPos);
        if (distSq <= ARRIVAL_DISTANCE_SQ) {
            restoreFarmland();
        } else if (navigationWatchdog.isStuck(villager, navigationTarget)) {
            failed = true;
            villager.getNavigation().stop();
        } else {
            // Keep the brain's walk target pointed at our repair position.
            // This must be re-set each tick so brain behaviors don't override it.
            setWalkTarget();
        }
    }

    @Override
    public void stop() {
        if (village != null && targetPos != null) {
            village.unclaimPosition(targetPos);
        }
        targetPos = null;
        navigationTarget = null;
        village = null;
        navigationWatchdog.reset();
        failed = false;
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    /**
     * Sets the brain's WALK_TARGET memory to the current repair target.
     * The brain's built-in SetWalkTargetFromMemory behavior handles navigation.
     */
    private void setWalkTarget() {
        if (targetPos != null) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(navigationTarget, SPEED_MODIFIER, WALK_TARGET_CLOSE_ENOUGH));
        }
    }

    private void restoreFarmland() {
        if (targetPos == null || village == null) {
            return;
        }
        if (!(villager.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        // Only convert dirt or grass blocks back to farmland
        BlockState currentState = serverLevel.getBlockState(targetPos);
        if (!currentState.is(Blocks.DIRT) && !currentState.is(Blocks.GRASS_BLOCK)) {
            // Not a tillable block: remove from repair queue and registry
            village.removeFarmland(targetPos);
            village.unclaimPosition(targetPos);
            VillageRegistryData data = VillageRegistryData.get(serverLevel);
            data.setDirty();
            targetPos = null;
            village = null;
            return;
        }

        // Place farmland block
        serverLevel.setBlock(targetPos, Blocks.FARMLAND.defaultBlockState(), 3);

        // Clean up tracking state
        village.removeFromRepairQueue(targetPos);
        village.unclaimPosition(targetPos);
        // Ensure the position remains in the farmland registry
        village.addFarmland(targetPos);

        VillageRegistryData data = VillageRegistryData.get(serverLevel);
        data.setDirty();

        targetPos = null;
        village = null;
    }

    /** Returns the current target position, for testing purposes. */
    @Nullable
    public BlockPos getTargetPos() {
        return targetPos;
    }
}
