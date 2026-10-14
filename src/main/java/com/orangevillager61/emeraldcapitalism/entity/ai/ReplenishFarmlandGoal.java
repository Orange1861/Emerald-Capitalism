package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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
    private static final int WALK_TARGET_CLOSE_ENOUGH = 0;
    private static final int EMPTY_QUEUE_RETRY_TICKS = 100;
    private static final int FAILED_TARGET_RETRY_TICKS = 100;

    private final Villager villager;
    @Nullable
    private BlockPos targetPos;
    @Nullable
    private BlockPos navigationTarget;
    @Nullable
    private VillageRecord village;
    private final VillagerNavigationWatchdog navigationWatchdog = new VillagerNavigationWatchdog();
    private final Map<BlockPos, Long> failedTargetExpiryTicks = new HashMap<>();
    private boolean failed;
    private boolean tryAlternativeRepairsDuringCooldown;
    private long nextQueueLookupTick;
    private final VillagerMovementRequestBinding movement = new VillagerMovementRequestBinding();

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
        long gameTime = serverLevel.getGameTime();
        removeExpiredFailedTargets(gameTime);
        if (gameTime < nextQueueLookupTick && !tryAlternativeRepairsDuringCooldown) {
            return false;
        }

        VillageRegistryData data = VillageRegistryData.get(serverLevel);
        VillageRecord found = data.getVillageFor(villager.blockPosition());
        if (found == null) {
            found = data.getNearestVillage(villager.blockPosition());
        }
        if (found == null) {
            scheduleNextQueueLookup(gameTime);
            tryAlternativeRepairsDuringCooldown = false;
            return false;
        }
        if (!found.isFarmlandRepairEnabled()) {
            scheduleNextQueueLookup(gameTime);
            tryAlternativeRepairsDuringCooldown = false;
            return false;
        }

        BlockPos nearest = findNearestAvailableRepair(found, villager.blockPosition(), gameTime);
        if (nearest == null) {
            tryAlternativeRepairsDuringCooldown = false;
            scheduleNextQueueLookup(gameTime);
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
                && village.getClaimedPositions().contains(targetPos)
                && movement.isActive(villager);
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
            navigationTarget = VillagerNavigationTargets.findReachableTargetOrFallback(
                    villager, targetPos, 2);
            if (navigationTarget == null) {
                village.unclaimPosition(targetPos);
                rememberFailedTarget(targetPos);
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

        // Check if arrived
        double distSq = villager.blockPosition().distSqr(targetPos);
        if (distSq <= ARRIVAL_DISTANCE_SQ) {
            movement.complete(villager);
            restoreFarmland();
        } else if (navigationWatchdog.isStuck(villager, navigationTarget)) {
            rememberFailedTarget(targetPos);
            failed = true;
            movement.cancel(villager, MovementRequestTerminationReason.FAILED);
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
        movement.cancel(villager, MovementRequestTerminationReason.ABANDONED);
    }

    /**
     * Sets the brain's WALK_TARGET memory to the current repair target.
     * The brain's built-in SetWalkTargetFromMemory behavior handles navigation.
     */
    private void setWalkTarget() {
        if (targetPos != null && navigationTarget != null) {
            if (!movement.submit(villager,
                    "emeraldcapitalism:farmland_repair",
                    "farmland-repair",
                    navigationTarget,
                    SPEED_MODIFIER,
                    WALK_TARGET_CLOSE_ENOUGH,
                    "farmland-interaction-v1",
                    "ground-ladder-v1",
                    MovementRequestManager.PRIORITY_WORK,
                    targetPos)) {
                failed = true;
            }
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

    private void rememberFailedTarget(BlockPos pos) {
        long gameTime = villager.level().getGameTime();
        failedTargetExpiryTicks.put(pos.immutable(), gameTime + FAILED_TARGET_RETRY_TICKS);
        nextQueueLookupTick = gameTime + FAILED_TARGET_RETRY_TICKS;
        tryAlternativeRepairsDuringCooldown = true;
    }

    private void removeExpiredFailedTargets(long gameTime) {
        Iterator<Map.Entry<BlockPos, Long>> iterator = failedTargetExpiryTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= gameTime) {
                iterator.remove();
            }
        }
    }

    @Nullable
    private BlockPos findNearestAvailableRepair(VillageRecord found, BlockPos origin, long gameTime) {
        double maxRangeSq = MAX_RANGE * MAX_RANGE;
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : found.getRepairQueue()) {
            if (found.getClaimedPositions().contains(pos)
                    || failedTargetExpiryTicks.getOrDefault(pos, Long.MIN_VALUE) > gameTime) {
                continue;
            }
            double distSq = origin.distSqr(pos);
            if (distSq <= maxRangeSq && distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = pos;
            }
        }
        return nearest;
    }

    private void scheduleNextQueueLookup(long gameTime) {
        long earliestExpiry = Long.MAX_VALUE;
        for (long expiry : failedTargetExpiryTicks.values()) {
            earliestExpiry = Math.min(earliestExpiry, expiry);
        }
        nextQueueLookupTick = earliestExpiry == Long.MAX_VALUE
                ? gameTime + EMPTY_QUEUE_RETRY_TICKS
                : earliestExpiry;
    }

    /** Returns the current target position, for testing purposes. */
    @Nullable
    public BlockPos getTargetPos() {
        return targetPos;
    }
}
