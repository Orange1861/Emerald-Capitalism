package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.LumberjackProductionAttachment;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.market.MarketDemandContext;
import com.orangevillager61.emeraldcapitalism.market.MarketItem;
import com.orangevillager61.emeraldcapitalism.market.MarketPricingEngine;
import com.orangevillager61.emeraldcapitalism.market.MarketRegistry;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.world.forestry.CharcoalProductionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/** Bounded lumberjack work loop for tree collection, replanting, and furnace production. */
public final class LumberjackGoal extends Goal {

    private static final int SEARCH_INTERVAL_TICKS = 40;
    private static final int EMPTY_SEARCH_INTERVAL_TICKS = 200;
    private static final int SEARCH_SLICE_INTERVAL_TICKS = 1;
    private static final int FURNACE_SEARCH_RANGE = 16;
    private static final int FURNACE_VERTICAL_SEARCH_RANGE = 6;
    private static final int FURNACE_SEARCH_INTERVAL_TICKS = 40;
    private static final int EMPTY_FURNACE_SEARCH_INTERVAL_TICKS = 40;
    private static final double ARRIVAL_DISTANCE_SQ = 9.0D;
    private static final double MAX_VERTICAL_REACH_ABOVE_HEAD = 10.0D;
    private static final float SPEED_MODIFIER = 0.6F;
    private static final int HAND_BREAK_TICKS_PER_HARDNESS = 30;
    private static final int NAVIGATION_RETRY_INTERVAL_TICKS = 10;
    private static final int JOB_SITE_SEARCH_RETRY_INTERVAL_TICKS = 100;
    private static final int BANK_LOOKUP_INTERVAL_TICKS = 20;
    private static final int MAX_NAVIGATION_FAILURES = 20;
    private static final int MAX_UPPER_TRUNK_APPROACHES = 4;
    private static final int FURNACE_INPUT_SLOT = 0;
    private static final int FURNACE_FUEL_SLOT = 1;
    private static final int FURNACE_RESULT_SLOT = 2;
    private static final int PLANKS_PER_LOG = 4;

    private final Villager villager;
    private final LumberjackTreeScanner treeScanner;
    @Nullable
    private LumberjackTreeScanner.TreeSnapshot tree;
    @Nullable
    private BlockPos navigationTarget;
    /** Standing position reserved with the tree, even while navigationTarget is cleared at work. */
    @Nullable
    private BlockPos reservedWorkPosition;
    @Nullable
    private BlockPos breakingPos;
    @Nullable
    private BlockPos productionFurnace;
    @Nullable
    private BlockPos jobSiteTarget;
    private int nextLogIndex;
    private int breakTicks;
    private int logsCollectedThisTree;
    private int lastBreakingStage = -1;
    private long nextSearchTick;
    private long nextFurnaceSearchTick;
    private long nextNavigationRetryTick;
    private long nextJobSiteReturnAttemptTick = Long.MIN_VALUE;
    private long nextBankLookupTick = Long.MIN_VALUE;
    private ServerLevel cachedBankLevel;
    @Nullable
    private BankBlockEntity cachedVillageBank;
    private int navigationFailures;
    private List<LumberjackTreeScanner.TreeSnapshot> candidateTrees = List.of();
    private int nextCandidateIndex;
    private int searchRange = LumberjackTreeScanner.INITIAL_SEARCH_RANGE;
    private final VillagerNavigationWatchdog navigationWatchdog = new VillagerNavigationWatchdog();
    private boolean furnaceInputInserted;
    private boolean returningToJobSite;
    private boolean failed;
    @Nullable
    private BlockPos cachedFurnaceSearchResult;

    public LumberjackGoal(Villager villager) {
        this.villager = villager;
        this.treeScanner = new LumberjackTreeScanner(villager);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !isBaseEligible()) {
            return false;
        }

        BankBlockEntity bank = findVillageBankCached(level);
        if (!isEligible(level, bank) || isDepositPending(bank)) {
            return false;
        }

        if (hasTrackedCharcoalProduction()) {
            if (resumeTrackedCharcoalProduction(level)) {
                return true;
            }
            // A tracked furnace in another dimension remains pending until the
            // villager returns. A missing or externally invalid furnace clears
            // its marker and may fall through to ordinary work below.
            if (hasTrackedCharcoalProduction()) {
                return false;
            }
        }

        if (hasReadyCharcoalBatch()) {
            FurnaceBlockEntity furnace = findNearestUsableFurnaceCached(level);
            if (furnace != null && canStartCharcoalProduction(furnace)) {
                productionFurnace = furnace.getBlockPos().immutable();
                furnaceInputInserted = false;
                navigationTarget = null;
                failed = false;
                return true;
            }
        }

        if (prepareJobSiteReturn(level)) {
            return true;
        }

        if (level.getGameTime() < nextSearchTick) {
            return false;
        }

        if (selectTrackedSaplingTree(level)) {
            treeScanner.resetSearch();
            searchRange = LumberjackTreeScanner.INITIAL_SEARCH_RANGE;
            nextSearchTick = level.getGameTime() + SEARCH_INTERVAL_TICKS;
            return true;
        }

        List<LumberjackTreeScanner.TreeSnapshot> candidates = PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.LUMBERJACK_SEARCH,
                () -> treeScanner.findCandidateTrees(level, searchRange));
        if (!treeScanner.isSearchComplete()) {
            // Tree discovery is resumable and uses a shared per-level budget.
            // Keep the cursor alive and advance it on the next AI pass instead
            // of turning an empty partial result into a failed search.
            nextSearchTick = level.getGameTime() + SEARCH_SLICE_INTERVAL_TICKS;
            return false;
        }
        if (selectTreeFromCandidates(level, candidates)) {
            treeScanner.resetSearch();
            searchRange = LumberjackTreeScanner.INITIAL_SEARCH_RANGE;
            nextSearchTick = level.getGameTime() + SEARCH_INTERVAL_TICKS;
            return true;
        }

        treeScanner.resetSearch();
        searchRange = nextSearchRange();
        nextSearchTick = level.getGameTime() + EMPTY_SEARCH_INTERVAL_TICKS;
        candidateTrees = List.of();
        nextCandidateIndex = 0;
        return false;
    }

    /**
     * Checks only saplings this lumberjack planted and shared lumbermill
     * saplings before entering the broad tree scan. The exact tree validator
     * remains authoritative, so a cache hit cannot select an invalid tree.
     */
    private boolean selectTrackedSaplingTree(ServerLevel level) {
        List<LumberjackSaplingCache.Candidate> grownSaplings =
                LumberjackSaplingCache.findGrownSaplings(
                        level, villager.getUUID(), villager.blockPosition(), searchRange, 16);
        for (LumberjackSaplingCache.Candidate sapling : grownSaplings) {
            if (LumberjackTreeReservations.isLogReservedByOther(
                    level, villager.getUUID(), List.of(sapling.position()))) {
                continue;
            }
            LumberjackTreeScanner.TreeSnapshot candidate = treeScanner.findCandidateTreeAt(
                    level, sapling.position());
            if (candidate == null) {
                continue;
            }
            if (selectTreeFromCandidates(level, List.of(candidate))) {
                return true;
            }
        }
        return false;
    }

    private int nextSearchRange() {
        if (searchRange >= LumberjackTreeScanner.MAX_SEARCH_RANGE) {
            return LumberjackTreeScanner.INITIAL_SEARCH_RANGE;
        }
        return Math.min(LumberjackTreeScanner.MAX_SEARCH_RANGE,
                searchRange + LumberjackTreeScanner.SEARCH_RANGE_INCREMENT);
    }

    private boolean selectTreeFromCandidates(ServerLevel level,
                                             List<LumberjackTreeScanner.TreeSnapshot> candidates) {
        candidateTrees = candidates;
        nextCandidateIndex = 0;
        return selectNextTree(level);
    }

    private boolean selectNextTree(ServerLevel level) {
        while (nextCandidateIndex < candidateTrees.size()) {
            LumberjackTreeScanner.TreeSnapshot candidate = candidateTrees.get(nextCandidateIndex++);
            if (LumberjackTreeReservations.isReservedByOther(
                    level, villager.getUUID(), candidate.logs())) {
                continue;
            }

            BlockPos approach = findReachableTreeApproach(level, candidate);
            if (approach == null
                    || !LumberjackTreeReservations.tryReserve(
                    level, villager.getUUID(), candidate.logs(), approach)) {
                continue;
            }

            tree = candidate;
            navigationTarget = approach;
            reservedWorkPosition = approach.immutable();
            nextLogIndex = 0;
            breakTicks = 0;
            logsCollectedThisTree = 0;
            navigationFailures = 0;
            failed = false;
            return true;
        }
        tree = null;
        navigationTarget = null;
        reservedWorkPosition = null;
        return false;
    }

    @Nullable
    private BlockPos findReachableTreeApproach(ServerLevel level,
                                               LumberjackTreeScanner.TreeSnapshot candidate) {
        BlockPos approach = VillagerNavigationTargets.findReachableTarget(
                villager, candidate.base(), 2,
                position -> {
                    BlockState state = getLoadedBlockState(level, position);
                    return state != null && state.isPathfindable(PathComputationType.LAND);
                });
        if (approach != null) {
            return approach;
        }

        // A supported trunk base is the normal standing point. The fallback
        // handles unusual trees whose base is enclosed but whose upper trunk
        // still has a reachable approach.
        int maxIndex = Math.min(candidate.logs().size(), MAX_UPPER_TRUNK_APPROACHES + 1);
        for (int index = 1; index < maxIndex; index++) {
            BlockPos log = candidate.logs().get(index);
            approach = VillagerNavigationTargets.findReachableTarget(
                    villager, log, 2,
                    position -> {
                        BlockState state = getLoadedBlockState(level, position);
                        return state != null && state.isPathfindable(PathComputationType.LAND);
                    });
            if (approach != null) {
                return approach;
            }
        }
        return null;
    }

    private boolean selectNextTreeAfterNavigationFailure(ServerLevel level) {
        clearBreakingProgress(level);
        allocateCharcoalQuota(level, logsCollectedThisTree);
        releaseTreeReservation();
        tree = null;
        navigationTarget = null;
        nextLogIndex = 0;
        breakTicks = 0;
        logsCollectedThisTree = 0;
        navigationWatchdog.reset();
        return selectNextTree(level);
    }

    @Override
    public boolean canContinueToUse() {
        if (!(villager.level() instanceof ServerLevel level) || !isBaseEligible()) {
            return false;
        }
        if (!returningToJobSite && tree == null && productionFurnace == null) {
            return false;
        }

        BankBlockEntity bank = findVillageBankCached(level);
        return !failed && isEligible(level, bank);
    }

    @Override
    public void start() {
        if (returningToJobSite) {
            failed = !moveToJobSite();
            navigationWatchdog.reset();
            return;
        }

        BlockPos target = currentTarget();
        if (target != null && villager.level() instanceof ServerLevel level) {
            clearLeafBlockingApproach(level, target);
        }
        if (target != null && isWithinWorkReach(target)) {
            holdPositionWhileWorking();
            failed = false;
        } else {
            failed = false;
            boolean started = productionFurnace == null
                    ? moveToCurrentTarget()
                    : moveToProductionFurnace();
            if (started) {
                navigationFailures = 0;
            } else if (villager.level() instanceof ServerLevel level) {
                recordNavigationFailure(level);
            }
        }
        navigationWatchdog.reset();
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }

        if (returningToJobSite) {
            tickReturnToJobSite(level);
            return;
        }

        if (tree == null) {
            if (productionFurnace != null) {
                tickCharcoalProduction(level);
            }
            return;
        }

        BlockPos target = currentTarget();
        if (target == null) {
            finishTree(level);
            return;
        }

        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(target));
        if (clearLeafBlockingApproach(level, target)) {
            // The old path may have ended at the leaf we just removed. Replan
            // from the villager's current position before trying to approach
            // the next log again.
            navigationTarget = null;
            navigationWatchdog.reset();
            navigationFailures = 0;
        }
        if (!isWithinWorkReach(target)) {
            if (navigationTarget == null) {
                if (level.getGameTime() < nextNavigationRetryTick) {
                    return;
                }
                if (moveToCurrentTarget()) {
                    navigationFailures = 0;
                } else {
                    recordNavigationFailure(level);
                }
            } else if (navigationTarget != null
                    && navigationWatchdog.isStuck(villager, navigationTarget)) {
                navigationTarget = null;
                navigationWatchdog.reset();
                recordNavigationFailure(level);
                villager.getNavigation().stop();
            } else if (villager.getNavigation().isDone()
                    && level.getGameTime() >= nextNavigationRetryTick) {
                // Re-evaluate a completed path from the villager's new position,
                // but never restart navigation every tick.
                navigationTarget = null;
                navigationWatchdog.reset();
                if (moveToCurrentTarget()) {
                    navigationFailures = 0;
                } else {
                    recordNavigationFailure(level);
                }
            }
            return;
        }

        // Once the lumberjack reaches a log, hold that work position. The
        // villager brain can otherwise retain a walk target and pull it around
        // while the block-breaking progress is still running.
        holdPositionWhileWorking();

        BlockState state = getLoadedBlockState(level, target);
        if (!treeScanner.isLumberjackLog(state)) {
            clearBreakingProgress(level);
            // The selected tree changed outside this goal. Abandon the stale
            // snapshot instead of walking the remaining positions and
            // replanting over another actor's work.
            releaseTreeReservation();
            tree = null;
            navigationTarget = null;
            nextLogIndex = 0;
            breakTicks = 0;
            logsCollectedThisTree = 0;
            nextSearchTick = level.getGameTime() + SEARCH_INTERVAL_TICKS;
            return;
        }

        if (breakingPos == null || !breakingPos.equals(target)) {
            clearBreakingProgress(level);
            breakingPos = target.immutable();
        }

        breakTicks++;
        int requiredBreakTicks = handBreakTicks(level, target, state);
        updateBreakingProgress(level, target, requiredBreakTicks);
        if (breakTicks >= requiredBreakTicks) {
            breakTicks = 0;
            clearBreakingProgress(level);
            logsCollectedThisTree += breakBlockAndCollect(level, target, state);
            nextLogIndex++;
            navigationTarget = null;
            navigationWatchdog.reset();
            navigationFailures = 0;
        }
    }

    private void holdPositionWhileWorking() {
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        navigationTarget = null;
        navigationFailures = 0;
        navigationWatchdog.reset();
    }

    private boolean isWithinWorkReach(BlockPos target) {
        double targetX = target.getX() + 0.5D;
        double targetY = target.getY() + 0.5D;
        double targetZ = target.getZ() + 0.5D;
        if (villager.distanceToSqr(targetX, targetY, targetZ) <= ARRIVAL_DISTANCE_SQ) {
            return true;
        }

        double horizontalDistanceSq = villager.distanceToSqr(targetX, villager.getY(), targetZ);
        double verticalGapAboveHead = target.getY() - villager.getBoundingBox().maxY;
        return horizontalDistanceSq <= ARRIVAL_DISTANCE_SQ
                && verticalGapAboveHead >= 0.0D
                && verticalGapAboveHead <= MAX_VERTICAL_REACH_ABOVE_HEAD;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        clearBreakingProgress();
        releaseTreeReservation();
        if (logsCollectedThisTree > 0 && villager.level() instanceof ServerLevel level) {
            allocateCharcoalQuota(level, logsCollectedThisTree);
        }
        tree = null;
        navigationTarget = null;
        reservedWorkPosition = null;
        productionFurnace = null;
        jobSiteTarget = null;
        furnaceInputInserted = false;
        returningToJobSite = false;
        nextLogIndex = 0;
        breakTicks = 0;
        logsCollectedThisTree = 0;
        breakingPos = null;
        lastBreakingStage = -1;
        navigationWatchdog.reset();
        nextNavigationRetryTick = 0L;
        navigationFailures = 0;
        candidateTrees = List.of();
        nextCandidateIndex = 0;
        failed = false;
    }

    private boolean isBaseEligible() {
        return villager.getVillagerData().getProfession() == ECAPVillagerProfessions.LUMBERJACK.get()
                && !villager.isSleeping()
                && !villager.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager);
    }

    private boolean isEligible(ServerLevel level, @Nullable BankBlockEntity bank) {
        return (lumberjackCuttingEnabled(bank) || hasTrackedCharcoalProduction())
                && (!isDepositPending(bank) || hasActiveWork())
                && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    private boolean lumberjackCuttingEnabled(@Nullable BankBlockEntity bank) {
        return bank == null || (bank.isVillagerDeliveriesEnabled()
                && bank.isLumberjackDeliveriesEnabled());
    }

    private boolean isDepositPending(@Nullable BankBlockEntity bank) {
        return bank != null && bank.isQueued(villager.getUUID());
    }

    @Nullable
    private BankBlockEntity findVillageBankCached(ServerLevel level) {
        long gameTime = level.getGameTime();
        if (gameTime < nextBankLookupTick
                && cachedBankLevel == level
                && (cachedVillageBank == null || !cachedVillageBank.isRemoved())) {
            return cachedVillageBank;
        }

        cachedBankLevel = level;
        nextBankLookupTick = gameTime + BANK_LOOKUP_INTERVAL_TICKS;
        cachedVillageBank = BankEmployeeLookup.findVillageBank(level, villager);
        return cachedVillageBank;
    }

    private boolean hasActiveWork() {
        return returningToJobSite || tree != null || productionFurnace != null;
    }

    private boolean prepareJobSiteReturn(ServerLevel level) {
        long gameTime = level.getGameTime();
        BlockPos jobSite = getJobSite(level);
        if (jobSite == null || isWithinWorkReach(jobSite)) {
            nextJobSiteReturnAttemptTick = gameTime;
            return false;
        }
        if (gameTime < nextJobSiteReturnAttemptTick) {
            return false;
        }

        jobSiteTarget = VillagerNavigationTargets.findReachableTarget(villager, jobSite, 2);
        if (jobSiteTarget == null) {
            // A stale or obstructed job site must not prevent the lumberjack
            // from scanning from its current position.
            nextJobSiteReturnAttemptTick = gameTime + JOB_SITE_SEARCH_RETRY_INTERVAL_TICKS;
            return false;
        }

        nextJobSiteReturnAttemptTick = gameTime + JOB_SITE_SEARCH_RETRY_INTERVAL_TICKS;
        returningToJobSite = true;
        navigationTarget = null;
        failed = false;
        return true;
    }

    private void tickReturnToJobSite(ServerLevel level) {
        BlockPos jobSite = getJobSite(level);
        if (jobSite == null || isWithinWorkReach(jobSite)) {
            holdPositionWhileWorking();
            jobSiteTarget = null;
            returningToJobSite = false;
            nextSearchTick = level.getGameTime();
            return;
        }

        if (jobSiteTarget == null && !moveToJobSite()) {
            failed = true;
            villager.getNavigation().stop();
        } else if (jobSiteTarget != null
                && navigationWatchdog.isStuck(villager, jobSiteTarget)) {
            failed = true;
            villager.getNavigation().stop();
        } else if (villager.getNavigation().isDone()
                && level.getGameTime() >= nextNavigationRetryTick) {
            jobSiteTarget = null;
            navigationWatchdog.reset();
            failed = !moveToJobSite();
        }
    }

    @Nullable
    private BlockPos getJobSite(ServerLevel level) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(globalPos -> globalPos.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .filter(pos -> {
                    BlockState state = getLoadedBlockState(level, pos);
                    return state != null && state.is(ECAPBlocks.SAWMILL.get());
                })
                .orElse(null);
    }

    private boolean moveToJobSite() {
        if (jobSiteTarget == null) {
            if (!(villager.level() instanceof ServerLevel level)) {
                return false;
            }
            BlockPos jobSite = getJobSite(level);
            if (jobSite == null || isWithinWorkReach(jobSite)) {
                return true;
            }
            jobSiteTarget = VillagerNavigationTargets.findReachableTarget(villager, jobSite, 2);
            if (jobSiteTarget == null) {
                return false;
            }
        }

        boolean started = villager.getNavigation().moveTo(
                jobSiteTarget.getX() + 0.5D, jobSiteTarget.getY() + 0.5D,
                jobSiteTarget.getZ() + 0.5D, SPEED_MODIFIER);
        if (started) {
            nextNavigationRetryTick = villager.level().getGameTime() + 20L;
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(jobSiteTarget, SPEED_MODIFIER, 1));
        }
        return started;
    }

    @Nullable
    private BlockPos currentTarget() {
        if (tree == null || nextLogIndex >= tree.logs().size()) {
            return null;
        }
        return tree.logs().get(nextLogIndex);
    }

    private boolean moveToCurrentTarget() {
        BlockPos target = currentTarget();
        if (target == null) {
            return false;
        }
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (navigationTarget == null) {
            navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, target, 2,
                    candidate -> {
                        BlockState state = getLoadedBlockState(level, candidate);
                        return state != null && state.isPathfindable(PathComputationType.LAND);
                    });
            if (navigationTarget == null) {
                return false;
            }
        }
        boolean started = villager.getNavigation().moveTo(
                    navigationTarget.getX() + 0.5D, navigationTarget.getY() + 0.5D,
                    navigationTarget.getZ() + 0.5D, SPEED_MODIFIER);
        if (started) {
            nextNavigationRetryTick = villager.level().getGameTime() + 20L;
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(navigationTarget, SPEED_MODIFIER, 1));
        } else {
            navigationTarget = null;
        }
        return started;
    }

    private void recordNavigationFailure(ServerLevel level) {
        navigationFailures++;
        nextNavigationRetryTick = level.getGameTime() + NAVIGATION_RETRY_INTERVAL_TICKS;
        if (navigationFailures >= MAX_NAVIGATION_FAILURES) {
            navigationFailures = 0;
            if (!selectNextTreeAfterNavigationFailure(level)) {
                searchRange = nextSearchRange();
                nextSearchTick = level.getGameTime() + EMPTY_SEARCH_INTERVAL_TICKS;
                failed = true;
                villager.getNavigation().stop();
            }
        }
    }

    /**
     * Removes selected-tree leaves occupying the villager's forward head
     * space. Leaves are normally cleared after the logs, but leaving part of
     * this space blocked can prevent navigation from reaching the next log.
     */
    private boolean clearLeafBlockingApproach(ServerLevel level, BlockPos target) {
        if (tree == null) {
            return false;
        }

        BlockPos origin = villager.blockPosition();
        int targetX = Integer.signum(target.getX() - origin.getX());
        int targetZ = Integer.signum(target.getZ() - origin.getZ());
        boolean cleared = false;
        for (int y = 1; y <= 2; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    if ((targetX != 0 || targetZ != 0)
                            && dx * targetX + dz * targetZ <= 0) {
                        continue;
                    }

                    BlockPos candidate = origin.offset(dx, y, dz);
                    if (!tree.leaves().contains(candidate)) {
                        continue;
                    }

                    BlockState state = getLoadedBlockState(level, candidate);
                    if (!treeScanner.isNaturalLeaf(state)) {
                        continue;
                    }

                    breakBlockAndCollect(level, candidate, state);
                    cleared = true;
                }
            }
        }
        return cleared;
    }

    private int handBreakTicks(ServerLevel level, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(hardness * HAND_BREAK_TICKS_PER_HARDNESS));
    }

    /**
     * Publishes the vanilla 0-9 block-crack stages while the lumberjack works.
     * The packet is sent by {@link ServerLevel} to nearby clients, so the
     * common/server goal does not need to reference any client-only classes.
     */
    private void updateBreakingProgress(ServerLevel level, BlockPos pos, int requiredBreakTicks) {
        int stage = Math.min(9, (int) ((long) breakTicks * 10L / requiredBreakTicks));
        if (stage != lastBreakingStage) {
            level.destroyBlockProgress(villager.getId(), pos, stage);
            lastBreakingStage = stage;
        }
    }

    private void clearBreakingProgress(ServerLevel level) {
        if (breakingPos != null) {
            level.destroyBlockProgress(villager.getId(), breakingPos, -1);
        }
        breakingPos = null;
        lastBreakingStage = -1;
    }

    private void clearBreakingProgress() {
        if (breakingPos != null && villager.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(villager.getId(), breakingPos, -1);
        }
        breakingPos = null;
        lastBreakingStage = -1;
    }

    private int breakBlockAndCollect(ServerLevel level, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, villager, ItemStack.EMPTY);
        if (level.destroyBlock(pos, false, villager)) {
            int collectedLogs = collectDrops(drops);
            // Vanilla clients interpret event 2001 as the block's break sound
            // plus its block debris particles.
            level.levelEvent(2001, pos, Block.getId(state));
            return collectedLogs;
        }
        return 0;
    }

    private void finishTree(ServerLevel level) {
        if (tree == null) {
            return;
        }

        clearBreakingProgress(level);

        for (BlockPos leafPos : tree.leaves()) {
            BlockState leafState = getLoadedBlockState(level, leafPos);
            if (leafState != null && leafState.is(BlockTags.LEAVES)) {
                List<ItemStack> drops = Block.getDrops(leafState, level, leafPos, null, villager, ItemStack.EMPTY);
                if (level.destroyBlock(leafPos, false, villager)) {
                    collectDrops(drops);
                }
            }
        }
        for (BlockPos log : tree.logs()) {
            LumberjackSaplingCache.forget(level, villager.getUUID(), log);
        }
        replant(level, tree.base(), tree.sapling());
        allocateCharcoalQuota(level, logsCollectedThisTree);
        beginCharcoalProduction(level);
        releaseTreeReservation();
        tree = null;
        nextLogIndex = 0;
        breakTicks = 0;
        logsCollectedThisTree = 0;
    }

    private int collectDrops(List<ItemStack> drops) {
        int collectedLogs = 0;
        for (ItemStack drop : drops) {
            ItemStack offered = drop.copy();
            if (!villager.wantsToPickUp(offered)) {
                villager.spawnAtLocation(offered);
                continue;
            }

            int offeredLogCount = offered.is(ItemTags.LOGS) ? offered.getCount() : 0;
            ItemStack remainder = villager.getInventory().addItem(offered);
            if (!remainder.isEmpty()) {
                villager.spawnAtLocation(remainder);
            }
            if (offeredLogCount > 0) {
                collectedLogs += offeredLogCount - remainder.getCount();
            }
        }
        return collectedLogs;
    }

    /** Adds only the logs actually inserted into the villager inventory to the
     * persistent quota. Retained logs are deliberately not counted again when
     * the next tree is harvested.
     */
    private void allocateCharcoalQuota(ServerLevel level, int newlyCollectedLogs) {
        if (newlyCollectedLogs <= 0) {
            return;
        }

        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        double share = charcoalShare(level);
        double quota = CharcoalProductionPolicy.accrueQuota(
                production.getCharcoalQuota(), newlyCollectedLogs, share);
        production.setCharcoalQuota(quota);
    }

    private void beginCharcoalProduction(ServerLevel level) {
        if (!hasReadyCharcoalBatch()) {
            return;
        }

        FurnaceBlockEntity furnace = findNearestUsableFurnaceCached(level);
        if (furnace == null || !canStartCharcoalProduction(furnace)) {
            return;
        }

        productionFurnace = furnace.getBlockPos().immutable();
        furnaceInputInserted = false;
        navigationTarget = null;
        navigationWatchdog.reset();
    }

    private boolean hasPendingCharcoalProduction() {
        return pendingCharcoalConversions() > 0;
    }

    private boolean hasReadyCharcoalBatch() {
        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        return CharcoalProductionPolicy.readyBatchConversions(
                production.getCharcoalQuota(), countLogsInInventory()) > 0;
    }

    private int pendingCharcoalConversions() {
        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        return CharcoalProductionPolicy.wholeConversions(
                production.getCharcoalQuota(), countLogsInInventory());
    }

    /** Returns whether an assigned eight-log batch also has a usable fuel source. */
    private boolean canStartCharcoalProduction(FurnaceBlockEntity furnace) {
        if (!hasReadyCharcoalBatch()) {
            return false;
        }
        if (!furnace.getItem(FURNACE_FUEL_SLOT).isEmpty()
                || countItem(Items.CHARCOAL) > 0) {
            return true;
        }
        Item fuelLog = findCompatibleLogForPlanks();
        return fuelLog != null
                && countLogsInInventory() > CharcoalProductionPolicy.MIN_CHARCOAL_BATCH_LOGS;
    }

    @Nullable
    private FurnaceBlockEntity findNearestUsableFurnaceCached(ServerLevel level) {
        long gameTime = level.getGameTime();
        if (gameTime < nextFurnaceSearchTick && cachedFurnaceSearchResult != null) {
            FurnaceBlockEntity cached = getUsableFurnace(level, cachedFurnaceSearchResult);
            if (cached != null) {
                return cached;
            }
            // The cached furnace became unavailable; immediately look for the
            // next candidate rather than waiting for the normal refresh interval.
            nextFurnaceSearchTick = gameTime;
        } else if (gameTime < nextFurnaceSearchTick) {
            return null;
        }

        FurnaceBlockEntity furnace = findNearestUsableFurnace(level);
        cachedFurnaceSearchResult = furnace == null ? null : furnace.getBlockPos().immutable();
        nextFurnaceSearchTick = gameTime + (furnace == null
                ? EMPTY_FURNACE_SEARCH_INTERVAL_TICKS : FURNACE_SEARCH_INTERVAL_TICKS);
        return furnace;
    }

    @Nullable
    private FurnaceBlockEntity getUsableFurnace(ServerLevel level, @Nullable BlockPos pos) {
        if (pos == null || !(BankEmployeeLookup.getLoadedBlockEntity(level, pos)
                instanceof FurnaceBlockEntity furnace)) {
            return null;
        }
        return furnace.getItem(FURNACE_INPUT_SLOT).isEmpty()
                && furnace.getItem(FURNACE_RESULT_SLOT).isEmpty() ? furnace : null;
    }

    @Nullable
    private FurnaceBlockEntity findNearestUsableFurnace(ServerLevel level) {
        BlockPos origin = villager.blockPosition();
        FurnaceBlockEntity nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();

        for (int x = -FURNACE_SEARCH_RANGE; x <= FURNACE_SEARCH_RANGE; x++) {
            for (int z = -FURNACE_SEARCH_RANGE; z <= FURNACE_SEARCH_RANGE; z++) {
                for (int y = -FURNACE_VERTICAL_SEARCH_RANGE; y <= FURNACE_VERTICAL_SEARCH_RANGE; y++) {
                    candidate.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!(BankEmployeeLookup.getLoadedBlockEntity(level, candidate)
                            instanceof FurnaceBlockEntity furnace)
                            || !furnace.getItem(FURNACE_INPUT_SLOT).isEmpty()
                            || !furnace.getItem(FURNACE_RESULT_SLOT).isEmpty()) {
                        continue;
                    }

                    double distanceSq = origin.distSqr(candidate);
                    if (distanceSq < nearestDistanceSq) {
                        nearest = furnace;
                        nearestDistanceSq = distanceSq;
                    }
                }
            }
        }
        return nearest;
    }

    private void tickCharcoalProduction(ServerLevel level) {
        if (productionFurnace == null) {
            return;
        }

        if (!level.hasChunk(productionFurnace.getX() >> 4, productionFurnace.getZ() >> 4)) {
            return;
        }
        if (!(BankEmployeeLookup.getLoadedBlockEntity(level, productionFurnace)
                instanceof FurnaceBlockEntity furnace)) {
            clearTrackedCharcoalProduction();
            finishCharcoalProduction();
            failed = true;
            return;
        }

        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                new BlockPosTracker(productionFurnace));
        if (!isWithinWorkReach(productionFurnace)) {
            if (navigationTarget == null && !moveToProductionFurnace()) {
                failed = true;
                villager.getNavigation().stop();
            } else if (navigationTarget != null
                    && navigationWatchdog.isStuck(villager, navigationTarget)) {
                failed = true;
                villager.getNavigation().stop();
            } else if (villager.getNavigation().isDone()
                    && level.getGameTime() >= nextNavigationRetryTick) {
                navigationTarget = null;
                navigationWatchdog.reset();
                failed = !moveToProductionFurnace();
            }
            return;
        }

        holdPositionWhileWorking();

        if (furnaceInputInserted) {
            if (furnace.getItem(FURNACE_INPUT_SLOT).isEmpty()
                    && furnace.getItem(FURNACE_RESULT_SLOT).isEmpty()) {
                clearTrackedCharcoalProduction();
                finishCharcoalProduction();
                return;
            }
            collectCharcoalFromFurnace(furnace);
            return;
        }

        if (!hasPendingCharcoalProduction()) {
            finishCharcoalProduction();
            return;
        }

        if (!furnace.getItem(FURNACE_INPUT_SLOT).isEmpty()
                || !furnace.getItem(FURNACE_RESULT_SLOT).isEmpty()) {
            // Never overwrite a furnace that another actor is using.
            finishCharcoalProduction();
            failed = true;
            return;
        }

        if (!canAccept(new ItemStack(Items.CHARCOAL))) {
            finishCharcoalProduction();
            failed = true;
            return;
        }

        Item fuelSource = null;
        ItemStack fuel = furnace.getItem(FURNACE_FUEL_SLOT);
        if (fuel.isEmpty()) {
            if (removeOneItem(Items.CHARCOAL)) {
                fuelSource = Items.CHARCOAL;
                fuel = new ItemStack(Items.CHARCOAL);
            } else {
                Item fuelLog = findCompatibleLogForPlanks();
                if (fuelLog == null || countLogsInInventory()
                        <= CharcoalProductionPolicy.MIN_CHARCOAL_BATCH_LOGS) {
                    // Preserve the quota and resume tree collection instead of
                    // waiting motionless at a furnace that cannot be fueled.
                    finishCharcoalProduction();
                    return;
                }
                removeOneItem(fuelLog);
                fuelSource = fuelLog;
                fuel = new ItemStack(planksForLog(fuelLog), PLANKS_PER_LOG);
            }
            furnace.setItem(FURNACE_FUEL_SLOT, fuel);
            furnace.setChanged();
        }

        Item inputLog = removeOneLog();
        if (inputLog == null) {
            if (fuelSource == Items.CHARCOAL) {
                villager.getInventory().addItem(new ItemStack(Items.CHARCOAL));
            } else if (fuelSource != null) {
                villager.getInventory().addItem(new ItemStack(fuelSource));
            }
            furnace.setItem(FURNACE_FUEL_SLOT, ItemStack.EMPTY);
            furnace.setChanged();
            return;
        }

        furnace.setItem(FURNACE_INPUT_SLOT, new ItemStack(inputLog));
        furnace.setChanged();
        // Charge the persistent quota when the log enters the furnace. The
        // goal can be interrupted before the result is collected, so charging
        // at collection time would allow an in-flight conversion to be
        // forgotten and counted again by a later goal instance.
        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        production.setCharcoalQuota(CharcoalProductionPolicy.afterConversions(
                production.getCharcoalQuota(), 1));
        production.setPendingCharcoalFurnace(GlobalPos.of(level.dimension(), productionFurnace));
        furnaceInputInserted = true;
    }

    private void collectCharcoalFromFurnace(FurnaceBlockEntity furnace) {
        ItemStack result = furnace.getItem(FURNACE_RESULT_SLOT);
        if (result.isEmpty()) {
            return;
        }
        if (!result.is(Items.CHARCOAL)) {
            clearTrackedCharcoalProduction();
            finishCharcoalProduction();
            failed = true;
            return;
        }
        if (!canAccept(result)) {
            failed = true;
            return;
        }

        ItemStack collected = result.copy();
        furnace.setItem(FURNACE_RESULT_SLOT, ItemStack.EMPTY);
        furnace.setChanged();
        ItemStack remainder = villager.getInventory().addItem(collected);
        if (!remainder.isEmpty()) {
            furnace.setItem(FURNACE_RESULT_SLOT, remainder);
            furnace.setChanged();
            return;
        }

        clearTrackedCharcoalProduction();
        furnaceInputInserted = false;
        if (!hasPendingCharcoalProduction()) {
            finishCharcoalProduction();
        }
    }

    private boolean hasTrackedCharcoalProduction() {
        return villager.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .getPendingCharcoalFurnace().isPresent();
    }

    private boolean resumeTrackedCharcoalProduction(ServerLevel level) {
        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        GlobalPos trackedFurnace = production.getPendingCharcoalFurnace().orElse(null);
        if (trackedFurnace == null) {
            return false;
        }
        if (!trackedFurnace.dimension().equals(level.dimension())) {
            return false;
        }

        BlockPos furnacePos = trackedFurnace.pos();
        if (!level.hasChunk(furnacePos.getX() >> 4, furnacePos.getZ() >> 4)) {
            return false;
        }
        if (!(BankEmployeeLookup.getLoadedBlockEntity(level, furnacePos)
                instanceof FurnaceBlockEntity furnace)) {
            clearTrackedCharcoalProduction();
            return false;
        }

        ItemStack input = furnace.getItem(FURNACE_INPUT_SLOT);
        ItemStack result = furnace.getItem(FURNACE_RESULT_SLOT);
        if (!result.isEmpty() && !result.is(Items.CHARCOAL)) {
            clearTrackedCharcoalProduction();
            return false;
        }
        if (!input.isEmpty() && !input.is(ItemTags.LOGS)) {
            clearTrackedCharcoalProduction();
            return false;
        }
        if (input.isEmpty() && result.isEmpty()) {
            clearTrackedCharcoalProduction();
            return false;
        }

        productionFurnace = furnacePos.immutable();
        furnaceInputInserted = true;
        navigationTarget = null;
        failed = false;
        return true;
    }

    private void clearTrackedCharcoalProduction() {
        villager.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .setPendingCharcoalFurnace(null);
    }

    private void finishCharcoalProduction() {
        productionFurnace = null;
        furnaceInputInserted = false;
        navigationTarget = null;
        navigationWatchdog.reset();
    }

    private boolean moveToProductionFurnace() {
        if (productionFurnace == null) {
            return false;
        }
        if (navigationTarget == null) {
            navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, productionFurnace, 2);
            if (navigationTarget == null) {
                return false;
            }
        }
        boolean started = villager.getNavigation().moveTo(
                navigationTarget.getX() + 0.5D, navigationTarget.getY() + 0.5D,
                navigationTarget.getZ() + 0.5D, SPEED_MODIFIER);
        if (started) {
            nextNavigationRetryTick = villager.level().getGameTime() + 20L;
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(navigationTarget, SPEED_MODIFIER, 1));
        }
        return started;
    }

    private double charcoalShare(ServerLevel level) {
        BankBlockEntity bank = findVillageBankCached(level);
        if (bank == null) {
            return CharcoalProductionPolicy.DEFAULT_SHARE;
        }

        MarketItem logs = MarketRegistry.get("oak_log").orElse(null);
        MarketItem charcoal = MarketRegistry.get("coal").orElse(null);
        if (logs == null || charcoal == null) {
            return CharcoalProductionPolicy.DEFAULT_SHARE;
        }

        int population = bank.getMarketPopulation(level);
        MarketDemandContext logContext = new MarketDemandContext(
                population, bank.getMarketTarget(level, logs));
        MarketDemandContext charcoalContext = new MarketDemandContext(
                population, bank.getMarketTarget(level, charcoal));
        double logRate = MarketPricingEngine.midRate(
                logs.config(), bank.getMarketStock(level, logs.item()), logContext);
        double charcoalRate = MarketPricingEngine.midRate(
                charcoal.config(), bank.getMarketStock(level, charcoal.item()), charcoalContext);
        return CharcoalProductionPolicy.charcoalShare(logRate, charcoalRate);
    }

    private int countLogsInInventory() {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItem(Item item) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Nullable
    private Item removeOneLog() {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS)) {
                Item item = stack.getItem();
                stack.shrink(1);
                villager.getInventory().setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                return item;
            }
        }
        return null;
    }

    private boolean removeOneItem(Item item) {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                stack.shrink(1);
                villager.getInventory().setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                return true;
            }
        }
        return false;
    }

    @Nullable
    private Item findCompatibleLogForPlanks() {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS) && planksForLog(stack.getItem()) != null) {
                return stack.getItem();
            }
        }
        return null;
    }

    @Nullable
    private Item planksForLog(Item log) {
        if (log == Items.OAK_LOG || log == Items.OAK_WOOD
                || log == Items.STRIPPED_OAK_LOG || log == Items.STRIPPED_OAK_WOOD) {
            return Items.OAK_PLANKS;
        }
        if (log == Items.SPRUCE_LOG || log == Items.SPRUCE_WOOD
                || log == Items.STRIPPED_SPRUCE_LOG || log == Items.STRIPPED_SPRUCE_WOOD) {
            return Items.SPRUCE_PLANKS;
        }
        if (log == Items.BIRCH_LOG || log == Items.BIRCH_WOOD
                || log == Items.STRIPPED_BIRCH_LOG || log == Items.STRIPPED_BIRCH_WOOD) {
            return Items.BIRCH_PLANKS;
        }
        if (log == Items.JUNGLE_LOG || log == Items.JUNGLE_WOOD
                || log == Items.STRIPPED_JUNGLE_LOG || log == Items.STRIPPED_JUNGLE_WOOD) {
            return Items.JUNGLE_PLANKS;
        }
        if (log == Items.ACACIA_LOG || log == Items.ACACIA_WOOD
                || log == Items.STRIPPED_ACACIA_LOG || log == Items.STRIPPED_ACACIA_WOOD) {
            return Items.ACACIA_PLANKS;
        }
        if (log == Items.DARK_OAK_LOG || log == Items.DARK_OAK_WOOD
                || log == Items.STRIPPED_DARK_OAK_LOG || log == Items.STRIPPED_DARK_OAK_WOOD) {
            return Items.DARK_OAK_PLANKS;
        }
        if (log == Items.MANGROVE_LOG || log == Items.MANGROVE_WOOD
                || log == Items.STRIPPED_MANGROVE_LOG || log == Items.STRIPPED_MANGROVE_WOOD) {
            return Items.MANGROVE_PLANKS;
        }
        return null;
    }

    private boolean canAccept(ItemStack incoming) {
        int remaining = incoming.getCount();
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stored = villager.getInventory().getItem(slot);
            if (stored.isEmpty()) {
                remaining -= incoming.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stored, incoming)) {
                remaining -= stored.getMaxStackSize() - stored.getCount();
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    private void replant(ServerLevel level, BlockPos base, Item preferredSapling) {
        BlockState baseState = getLoadedBlockState(level, base);
        if (baseState == null || !baseState.isAir()) {
            return;
        }

        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (!stack.is(preferredSapling)) {
                continue;
            }

            Block saplingBlock = Block.byItem(stack.getItem());
            BlockState saplingState = saplingBlock.defaultBlockState();
            if (!saplingState.canSurvive(level, base)) {
                continue;
            }

            if (!level.setBlock(base, saplingState, 3)) {
                continue;
            }
            LumberjackSaplingCache.trackPlaced(level, villager.getUUID(), base, saplingBlock);
            stack.shrink(1);
            villager.getInventory().setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            return;
        }
    }

    @Nullable
    private static BlockState getLoadedBlockState(ServerLevel level, BlockPos pos) {
        return BankEmployeeLookup.getLoadedBlockState(level, pos);
    }

    private void releaseTreeReservation() {
        if (tree != null && villager.level() instanceof ServerLevel level) {
            LumberjackTreeReservations.release(level, villager.getUUID(), tree.logs(), reservedWorkPosition);
        }
        reservedWorkPosition = null;
    }

}
