package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageWorkCell;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Executes event-driven mature-crop work after a farmer receives a temporary
 * village work-cell lease. Vanilla HarvestFarmland remains responsible for its
 * ordinary local crop discovery and behavior.
 */
public final class HarvestQueuedCropGoal extends Goal {

    public static final int GOAL_PRIORITY = 4;
    private static final double MAX_RANGE = 32.0D;
    private static final double ARRIVAL_DISTANCE_SQ = 2.25D;
    private static final float SPEED_MODIFIER = 0.6F;
    /** Match vanilla HarvestFarmland's adjacent interaction target. */
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
    private final VillagerMovementRequestBinding movement = new VillagerMovementRequestBinding();
    private boolean failed;
    private long nextQueueLookupTick;
    private boolean tryAlternativeCropsDuringCooldown;

    public HarvestQueuedCropGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!villager.isAlive()
                || villager.isBaby()
                || villager.isSleeping()
                || villager.isTrading()
                || villager.getVillagerData().getProfession() != VillagerProfession.FARMER
                || VillagerBreedingSessions.shouldYieldCustomWork(villager)
                || isSocialInteractionActive()
                || !(villager.level() instanceof ServerLevel level)
                || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                || isVanillaJobSiteWorkActive(level)) {
            return false;
        }

        long gameTime = level.getGameTime();
        removeExpiredFailedTargets(gameTime);
        if (gameTime < nextQueueLookupTick && !tryAlternativeCropsDuringCooldown) {
            return false;
        }

        VillageRegistryData data = VillageRegistryData.get(level);
        VillageRecord assignedVillage = data.getVillageFor(villager.blockPosition());
        if (assignedVillage == null || !assignedVillage.hasMember(villager.getUUID())
                || !assignedVillage.ensureFarmerWorkLease(level, villager, gameTime)) {
            scheduleNextQueueLookup(gameTime);
            tryAlternativeCropsDuringCooldown = false;
            return false;
        }

        VillageWorkCell leasedCell = assignedVillage.getWorkCellLeasedTo(villager.getUUID());
        if (leasedCell == null || leasedCell.getPendingCropWorkCount() == 0) {
            releaseLease(assignedVillage, leasedCell);
            scheduleNextQueueLookup(gameTime);
            tryAlternativeCropsDuringCooldown = false;
            return false;
        }

        BlockPos nearest = assignedVillage.getNearestUnclaimedMatureCrop(
                villager.blockPosition(), MAX_RANGE, leasedCell.getKey(),
                pos -> failedTargetExpiryTicks.getOrDefault(pos, Long.MIN_VALUE) <= gameTime);
        if (nearest == null) {
            scheduleNextQueueLookup(gameTime);
            tryAlternativeCropsDuringCooldown = false;
            return false;
        }

        village = assignedVillage;
        targetPos = nearest;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetPos == null || village == null
                || failed
                || !villager.isAlive()
                || villager.getVillagerData().getProfession() != VillagerProfession.FARMER
                || !village.hasMember(villager.getUUID())
                || VillagerBreedingSessions.shouldYieldCustomWork(villager)
                || isSocialInteractionActive()
                || !(villager.level() instanceof ServerLevel level)
                || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                || isVanillaJobSiteWorkActive(level)
                || !village.renewWorkLease(villager.getUUID(), targetPos, level.getGameTime())) {
            return false;
        }
        // The movement manager may retire a request on the same tick that the
        // villager reaches its standing position. Keep the goal alive for that
        // arrival tick so tick() can perform the harvest instead of stop()
        // releasing the claim before the crop is broken.
        boolean arrived = villager.blockPosition().distSqr(targetPos) <= ARRIVAL_DISTANCE_SQ;
        return village.getMatureCropWorkQueue().contains(targetPos)
                && village.getClaimedMatureCropPositions().contains(targetPos)
                && village.hasWorkLease(villager.getUUID(), targetPos)
                // The sink may retire or lose a route before the next goal
                // tick. Keep the temporary claim while the request is still
                // recoverable so the binding can publish it again.
                && (arrived || !failed);
    }

    @Override
    public void start() {
        if (village == null || targetPos == null) {
            return;
        }

        // The claim is made before computing or submitting navigation. A
        // second farmer therefore cannot begin the same queued crop task.
        if (!village.claimMatureCropPosition(villager.getUUID(), targetPos)) {
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP][CropQueue] CLAIM_LOST farmer={} target={}",
                    villager.getUUID(), targetPos);
            releaseLease(village, targetPos);
            targetPos = null;
            village = null;
            return;
        }

        if (!(villager.level() instanceof ServerLevel level)
                || !isQueuedCropStillValid(level)) {
            finishWithoutHarvest(levelOrNull());
            return;
        }

        // Vanilla HarvestFarmland publishes the crop block itself and lets
        // MoveToTargetSink choose an adjacent path node.  Keeping a separate
        // precomputed standing node can reject a valid route when the path
        // snapshot used for selection is stale.
        navigationTarget = targetPos;

        failed = false;
        navigationWatchdog.reset();
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP][CropQueue] START farmer={} village={} target={} cell={}",
                villager.getUUID(), village.getVillageId(), targetPos,
                VillageWorkCell.Key.from(targetPos));
        setWalkTarget();
    }

    @Override
    public void tick() {
        if (targetPos == null || village == null || navigationTarget == null) {
            return;
        }

        double distanceSq = villager.blockPosition().distSqr(targetPos);
        if (distanceSq <= ARRIVAL_DISTANCE_SQ) {
            movement.complete(villager);
            harvestQueuedCrop();
        } else if (navigationWatchdog.isStuck(villager, navigationTarget)) {
            rememberFailedTarget(targetPos);
            failed = true;
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP][CropQueue] PATH_FAILED farmer={} target={} pos={}",
                    villager.getUUID(), targetPos, villager.blockPosition());
            if (villager.level() instanceof ServerLevel level) {
                village.reportWorkLeaseFailure(villager.getUUID(), targetPos, level.getGameTime());
            }
            movement.cancel(villager, MovementRequestTerminationReason.FAILED);
        } else {
            setWalkTarget();
        }
    }

    @Override
    public void stop() {
        if (village != null && targetPos != null) {
            village.releaseMatureCropPositionClaim(villager.getUUID(), targetPos);
            if (villager.level() instanceof ServerLevel level && failed) {
                village.reportWorkLeaseFailure(villager.getUUID(), targetPos, level.getGameTime());
            } else {
                village.releaseWorkLease(villager.getUUID(), targetPos);
            }
        }
        targetPos = null;
        navigationTarget = null;
        village = null;
        failed = false;
        navigationWatchdog.reset();
        movement.cancel(villager, MovementRequestTerminationReason.ABANDONED);
    }

    private void harvestQueuedCrop() {
        if (targetPos == null || village == null || !(villager.level() instanceof ServerLevel level)) {
            return;
        }

        if (!isQueuedCropStillValid(level)) {
            finishWithoutHarvest(level);
            return;
        }

        BlockPos harvestedPosition = targetPos;
        if (!level.destroyBlock(harvestedPosition, true, villager)) {
            failed = true;
            village.reportWorkLeaseFailure(villager.getUUID(), harvestedPosition, level.getGameTime());
            return;
        }

        village.removeMatureCropWork(harvestedPosition);
        boolean replanted = replantWithValidVillagerSeed(level, harvestedPosition);
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP][CropQueue] HARVESTED farmer={} target={} replanted={}",
                villager.getUUID(), harvestedPosition, replanted);
        VillageRegistryData.get(level).setDirty();
        releaseLease(village, harvestedPosition);
        targetPos = null;
        navigationTarget = null;
        village = null;
    }

    private boolean isQueuedCropStillValid(ServerLevel level) {
        if (targetPos == null || village == null) {
            return false;
        }
        boolean changed = village.validateMatureCropWork(level, targetPos);
        if (changed) {
            VillageRegistryData.get(level).setDirty();
        }
        return village.getMatureCropWorkQueue().contains(targetPos);
    }

    private boolean replantWithValidVillagerSeed(ServerLevel level, BlockPos position) {
        if (!villager.hasFarmSeeds() || !level.getBlockState(position).isAir()) {
            return false;
        }

        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack seedStack = villager.getInventory().getItem(slot);
            if (!seedStack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)
                    || !(seedStack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }

            BlockState plantedState = blockItem.getBlock().defaultBlockState();
            if (!plantedState.canSurvive(level, position)
                    || !level.setBlock(position, plantedState, 3)) {
                continue;
            }

            level.gameEvent(GameEvent.BLOCK_PLACE, position,
                    GameEvent.Context.of(villager, plantedState));
            seedStack.shrink(1);
            return true;
        }
        return false;
    }

    private void setWalkTarget() {
        if (targetPos == null || navigationTarget == null) {
            return;
        }
        if (!movement.submit(villager,
                "emeraldcapitalism:queued_crop_harvest",
                "queued-crop-harvest",
                targetPos,
                SPEED_MODIFIER,
                WALK_TARGET_CLOSE_ENOUGH,
                "crop-interaction-v1",
                "ground-ladder-v1",
                MovementRequestManager.PRIORITY_WORK,
                targetPos)) {
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP][CropQueue] MOVE_REJECTED farmer={} target={} pos={}",
                    villager.getUUID(), targetPos, villager.blockPosition());
            failed = true;
        }
    }

    private void finishWithoutHarvest(@Nullable ServerLevel level) {
        if (village != null && targetPos != null) {
            village.releaseMatureCropPositionClaim(villager.getUUID(), targetPos);
            village.releaseWorkLease(villager.getUUID(), targetPos);
            if (level != null) {
                VillageRegistryData.get(level).setDirty();
            }
        }
        targetPos = null;
        navigationTarget = null;
        village = null;
    }

    private void releaseLease(VillageRecord assignedVillage, @Nullable VillageWorkCell cell) {
        if (cell != null && cell.getWorkPosition() != null) {
            assignedVillage.releaseWorkLease(villager.getUUID(), cell.getWorkPosition());
        }
    }

    private void releaseLease(VillageRecord assignedVillage, BlockPos position) {
        assignedVillage.releaseMatureCropPositionClaim(villager.getUUID(), position);
        assignedVillage.releaseWorkLease(villager.getUUID(), position);
    }

    @Nullable
    private ServerLevel levelOrNull() {
        return villager.level() instanceof ServerLevel level ? level : null;
    }

    private void rememberFailedTarget(BlockPos position) {
        long gameTime = villager.level().getGameTime();
        failedTargetExpiryTicks.put(position.immutable(), gameTime + FAILED_TARGET_RETRY_TICKS);
        nextQueueLookupTick = gameTime + FAILED_TARGET_RETRY_TICKS;
        tryAlternativeCropsDuringCooldown = true;
    }

    private void removeExpiredFailedTargets(long gameTime) {
        Iterator<Map.Entry<BlockPos, Long>> iterator = failedTargetExpiryTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= gameTime) {
                iterator.remove();
            }
        }
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

    /** Keeps custom crop work from taking movement away from the farmer's vanilla job site. */
    private boolean isVanillaJobSiteWorkActive(ServerLevel level) {
        var brain = villager.getBrain();
        var jobSite = brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                .orElse(null);
        if (jobSite == null || jobSite.dimension() != level.dimension()) {
            return false;
        }
        Long lastWorkedAtPoi = brain.getMemory(
                MemoryModuleType.LAST_WORKED_AT_POI).orElse(null);
        if (lastWorkedAtPoi != null && level.getGameTime() - lastWorkedAtPoi <= 1L) {
            return true;
        }
        // GoToJobSite leaves a stale WALK_TARGET behind after the farmer has
        // arrived. Only treat it as active job-site travel while the farmer is
        // still outside WorkAtPoi's interaction radius.
        return brain.getMemory(MemoryModuleType.WALK_TARGET)
                .map(walkTarget -> walkTarget.getTarget().currentBlockPosition().equals(jobSite.pos())
                        && !jobSite.pos().closerToCenterThan(villager.position(), 1.73D))
                .orElse(false);
    }

    /** Social villager behavior must finish before queued work takes MOVE/LOOK. */
    private boolean isSocialInteractionActive() {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.INTERACTION_TARGET)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET);
    }

    /** Returns the current queued crop target for focused navigation regressions. */
    @Nullable
    public BlockPos getTargetPos() {
        return targetPos;
    }
}
