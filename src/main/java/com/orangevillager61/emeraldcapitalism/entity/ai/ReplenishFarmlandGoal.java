package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageWorkCell;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
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
    /** Match vanilla farmer interaction: the farmer stands beside the task block. */
    private static final int WALK_TARGET_CLOSE_ENOUGH = 1;
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
        if (!villager.isAlive()
                || villager.isBaby()
                || villager.isSleeping()
                || villager.isTrading()
                || villager.getVillagerData().getProfession() != VillagerProfession.FARMER) {
            return false;
        }
        if (isSocialInteractionActive()) {
            return false;
        }
        if (!(villager.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (isVanillaJobSiteWorkActive(serverLevel)) {
            return false;
        }
        long gameTime = serverLevel.getGameTime();
        removeExpiredFailedTargets(gameTime);
        if (gameTime < nextQueueLookupTick && !tryAlternativeRepairsDuringCooldown) {
            return false;
        }

        VillageRegistryData data = VillageRegistryData.get(serverLevel);
        VillageRecord assignedVillage = data.getVillageFor(villager.blockPosition());
        if (assignedVillage == null || !assignedVillage.hasMember(villager.getUUID())) {
            scheduleNextQueueLookup(gameTime);
            tryAlternativeRepairsDuringCooldown = false;
            return false;
        }
        if (!assignedVillage.isFarmlandRepairEnabled()) {
            scheduleNextQueueLookup(gameTime);
            tryAlternativeRepairsDuringCooldown = false;
            return false;
        }

        if (!assignedVillage.ensureFarmerWorkLease(serverLevel, villager, gameTime)) {
            scheduleNextQueueLookup(gameTime);
            tryAlternativeRepairsDuringCooldown = false;
            return false;
        }
        VillageWorkCell leasedCell = assignedVillage.getWorkCellLeasedTo(villager.getUUID());
        if (leasedCell == null || leasedCell.getPendingRepairCount() == 0) {
            if (leasedCell != null && leasedCell.getWorkPosition() != null) {
                assignedVillage.releaseWorkLease(villager.getUUID(), leasedCell.getWorkPosition());
            }
            scheduleNextQueueLookup(gameTime);
            tryAlternativeRepairsDuringCooldown = false;
            return false;
        }

        BlockPos nearest = findNearestAvailableRepair(
                assignedVillage, villager.blockPosition(), gameTime, leasedCell.getKey());
        if (nearest == null) {
            tryAlternativeRepairsDuringCooldown = false;
            scheduleNextQueueLookup(gameTime);
            return false;
        }

        this.village = assignedVillage;
        this.targetPos = nearest;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetPos == null || village == null
                || !villager.isAlive()
                || villager.getVillagerData().getProfession() != VillagerProfession.FARMER
                || !village.hasMember(villager.getUUID())
                || VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return false;
        }
        if (isSocialInteractionActive()) {
            return false;
        }
        if (failed || !(villager.level() instanceof ServerLevel serverLevel)
                || !village.renewWorkLease(villager.getUUID(), targetPos, serverLevel.getGameTime())) {
            return false;
        }
        if (isVanillaJobSiteWorkActive(serverLevel)) {
            return false;
        }
        // Continue only while this goal still owns a valid queued target.
        //
        // Why include claimedPositions?
        // - canUse() selects an unclaimed target, but another farmer can still race us
        //   before start() claims it.
        // - if claims are lost/reset while running, this goal should stop immediately
        //   and allow normal arbitration to pick a fresh target next tick.
        // The movement request can complete on the same tick that the farmer
        // reaches its standing position. Keep the goal alive for that arrival
        // tick so tick() can restore the farmland before stop() releases it.
        boolean arrived = villager.blockPosition().distSqr(targetPos) <= ARRIVAL_DISTANCE_SQ;
        return village.getRepairQueue().contains(targetPos)
                && village.getClaimedPositions().contains(targetPos)
                && village.hasWorkLease(villager.getUUID(), targetPos)
                // A failed/preempted sink route is recoverable. Keep the
                // queued claim alive so tick() can republish the same request
                // after the vanilla sink's retry window instead of stopping
                // the work goal permanently.
                && (arrived || !failed);
    }

    @Override
    public void start() {
        if (village != null && targetPos != null) {
            // If claiming fails, another farmer took this position between canUse() and start().
            // Abort this run so goal arbitration can pick a fresh target next cycle.
            if (!village.claimRepairPosition(villager.getUUID(), targetPos)) {
                village.releaseWorkLease(villager.getUUID(), targetPos);
                targetPos = null;
                village = null;
                return;
            }
            // A queued repair position is a solid dirt/grass block, unlike the
            // crop block targeted by vanilla HarvestFarmland.  Target the open
            // block above it so MoveToTargetSink can build a normal route; the
            // repair position remains the authoritative interaction target.
            navigationTarget = targetPos.above();
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
            if (villager.level() instanceof ServerLevel level) {
                village.reportWorkLeaseFailure(villager.getUUID(), targetPos, level.getGameTime());
            }
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
            village.releaseRepairPositionClaim(villager.getUUID(), targetPos);
            if (failed && villager.level() instanceof ServerLevel level) {
                village.reportWorkLeaseFailure(villager.getUUID(), targetPos, level.getGameTime());
            } else {
                village.releaseWorkLease(villager.getUUID(), targetPos);
            }
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
            village.releaseWorkLease(villager.getUUID(), targetPos);
            village.removeFarmland(targetPos);
            village.releaseRepairPositionClaim(villager.getUUID(), targetPos);
            VillageRegistryData data = VillageRegistryData.get(serverLevel);
            data.setDirty();
            targetPos = null;
            village = null;
            return;
        }

        // Place farmland block
        serverLevel.setBlock(targetPos, Blocks.FARMLAND.defaultBlockState(), 3);

        // Clean up tracking state
        village.releaseWorkLease(villager.getUUID(), targetPos);
        village.removeFromRepairQueue(targetPos);
        village.releaseRepairPositionClaim(villager.getUUID(), targetPos);
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
    private BlockPos findNearestAvailableRepair(VillageRecord found, BlockPos origin, long gameTime,
                                                VillageWorkCell.Key cellKey) {
        return found.getNearestUnclaimedRepair(origin, MAX_RANGE, cellKey,
                pos -> failedTargetExpiryTicks.getOrDefault(pos, Long.MIN_VALUE) <= gameTime);
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

    /** Keeps custom repair work from taking movement away from the farmer's vanilla job site. */
    private boolean isVanillaJobSiteWorkActive(ServerLevel level) {
        GlobalPos jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (jobSite == null || jobSite.dimension() != level.dimension()) {
            return false;
        }
        Long lastWorkedAtPoi = villager.getBrain().getMemory(MemoryModuleType.LAST_WORKED_AT_POI).orElse(null);
        if (lastWorkedAtPoi != null && level.getGameTime() - lastWorkedAtPoi <= 1L) {
            return true;
        }

        WalkTarget walkTarget = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        return walkTarget != null
                && walkTarget.getTarget().currentBlockPosition().equals(jobSite.pos())
                && !jobSite.pos().closerToCenterThan(villager.position(), 1.73D);
    }

    /** Social villager behavior must finish before custom work takes MOVE/LOOK. */
    private boolean isSocialInteractionActive() {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.INTERACTION_TARGET)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET);
    }

    /** Returns the current target position, for testing purposes. */
    @Nullable
    public BlockPos getTargetPos() {
        return targetPos;
    }
}
