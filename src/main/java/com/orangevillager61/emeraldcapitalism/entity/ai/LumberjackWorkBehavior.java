package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.LumberjackProductionAttachment;
import com.orangevillager61.emeraldcapitalism.behavior.BrainMovementIntent;
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
import com.orangevillager61.emeraldcapitalism.world.forestry.FurnaceClaim;
import com.orangevillager61.emeraldcapitalism.world.forestry.FurnacePool;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The lumberjack's whole job as one Brain-owned task: job-site return, tree search and
 * selection, travel to a tree, consecutive log work, drop collection, replanting, and charcoal
 * production in a village-shared furnace.
 *
 * <p>Caller: {@code VillagerGoalPackageIntegration#addWorkBehaviors}, which adds one instance to
 * the lumberjack's WORK package. The Brain is the only scheduler. The behavior starts only while
 * {@code WALK_TARGET} is absent, publishes every journey through {@link BrainMovementIntent}, and
 * lets vanilla {@code MoveToTargetSink} walk it; it never creates a path of its own.
 *
 * <p>Work-slot rule: for a lumberjack the composition removes vanilla's job-site movers
 * ({@code SetWalkTargetFromBlockMemory} and the strolling gate) so nothing but this behavior
 * and genuine interruptions writes {@code WALK_TARGET} during work. Another writer (panic, boat,
 * trade, the inventory-bank trip) holds the target while it runs; this behavior waits, yields if
 * its own target is replaced, and reacquires afterwards. It keeps no task state across a stop
 * except what the village's furnace pool and the saved production attachment hold.
 *
 * <p>Furnaces: the village's {@link FurnacePool} is the only source. A claim reserves one furnace
 * and holds no items; this behavior loads logs and fuel itself, leaves while the furnace works,
 * and returns at a stage boundary (after a tree) to collect. Interruptions never release the
 * claim; the village takes it over when the lumberjack dies, unloads, or changes profession.
 */
public final class LumberjackWorkBehavior extends Behavior<Villager> {

    /** Ahead of vanilla's WORK-package movers, which the lumberjack composition removes. */
    public static final int PRIORITY = 1;

    private static final int RUN_TICKS = 240_000;
    private static final int SEARCH_INTERVAL_TICKS = 40;
    private static final int EMPTY_SEARCH_INTERVAL_TICKS = 200;
    private static final int SEARCH_SLICE_INTERVAL_TICKS = 1;
    private static final int LOCAL_TREE_PROBE_HORIZONTAL_RANGE = 4;
    private static final int LOCAL_TREE_PROBE_VERTICAL_RANGE = 4;
    private static final int LOCAL_TREE_PROBE_MAX_POSITIONS =
            (LOCAL_TREE_PROBE_HORIZONTAL_RANGE * 2 + 1) * (LOCAL_TREE_PROBE_VERTICAL_RANGE * 2 + 1)
                    * (LOCAL_TREE_PROBE_HORIZONTAL_RANGE * 2 + 1);
    private static final int LOCAL_RESERVED_RETRY_INTERVAL_TICKS = 10;
    private static final double ARRIVAL_DISTANCE_SQ = 9.0D;
    private static final double MAX_VERTICAL_REACH_ABOVE_HEAD = 10.0D;
    private static final float SPEED_MODIFIER = 0.6F;
    private static final int CLOSE_ENOUGH = 1;
    private static final long JOURNEY_TIMEOUT_TICKS = 600L;
    private static final int HAND_BREAK_TICKS_PER_HARDNESS = 30;
    private static final int NAVIGATION_RETRY_INTERVAL_TICKS = 10;
    private static final int JOB_SITE_SEARCH_RETRY_INTERVAL_TICKS = 100;
    private static final int BANK_LOOKUP_INTERVAL_TICKS = 20;
    private static final int MAX_NAVIGATION_FAILURES = 20;
    private static final int MAX_UPPER_TRUNK_APPROACHES = 4;
    private static final int PRODUCTION_ASK_INTERVAL_TICKS = 100;
    private static final int FULL_INVENTORY_RECHECK_TICKS = 20;
    private static final int FURNACE_INPUT_SLOT = 0;
    private static final int FURNACE_FUEL_SLOT = 1;
    private static final int FURNACE_RESULT_SLOT = 2;
    private static final int PLANKS_PER_LOG = 4;
    private static final int FUEL_ITEMS_PER_CHARCOAL = 8;

    /** Why the task gave up its current plan; replaces a single failed flag. */
    private enum Outcome {
        RUNNING,
        NO_REACHABLE_TREE,
        JOB_SITE_UNREACHABLE,
        PRODUCTION_BLOCKED
    }

    /** What the furnace trip is for. */
    private enum Trip {
        NONE,
        LOAD,
        COLLECT
    }

    private final BrainMovementIntent intent = new BrainMovementIntent(
            "emeraldcapitalism:lumberjack_work", false, JOURNEY_TIMEOUT_TICKS);
    private LumberjackTreeScanner treeScanner;
    @Nullable
    private LumberjackTreeScanner.TreeSnapshot tree;
    @Nullable
    private BlockPos navigationTarget;
    @Nullable
    private BlockPos reservedWorkPosition;
    @Nullable
    private BlockPos breakingPos;
    @Nullable
    private BlockPos jobSiteTarget;
    private int nextLogIndex;
    private int breakTicks;
    private int logsCollectedThisTree;
    private int lastBreakingStage = -1;
    private long nextSearchTick;
    private long nextNavigationRetryTick;
    private long nextJobSiteReturnAttemptTick = Long.MIN_VALUE;
    private long nextBankLookupTick = Long.MIN_VALUE;
    private long nextProductionAskTick = Long.MIN_VALUE;
    private long nextCollectCheckTick = Long.MIN_VALUE;
    private long nextInventoryRecheckTick = Long.MIN_VALUE;
    @Nullable
    private ServerLevel cachedBankLevel;
    @Nullable
    private BankBlockEntity cachedVillageBank;
    private int navigationFailures;
    private List<LumberjackTreeScanner.TreeSnapshot> candidateTrees = List.of();
    private int nextCandidateIndex;
    private int searchRange = LumberjackTreeScanner.INITIAL_SEARCH_RANGE;
    private final VillagerNavigationWatchdog navigationWatchdog = new VillagerNavigationWatchdog();
    private boolean returningToJobSite;
    private Trip trip = Trip.NONE;
    private Outcome outcome = Outcome.RUNNING;
    private long lastWaitingForFurnaceTick = Long.MIN_VALUE;

    public LumberjackWorkBehavior() {
        super(Map.of(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT), RUN_TICKS, RUN_TICKS);
    }

    // ------------------------------------------------------------------ start

    @Override
    protected boolean checkExtraStartConditions(@Nonnull ServerLevel level, @Nonnull Villager villager) {
        if (!isBaseEligible(villager)) {
            return false;
        }
        BankBlockEntity bank = findVillageBankCached(level, villager);
        if (!isEligible(level, villager, bank)) {
            return false;
        }
        long gameTime = level.getGameTime();

        // A saved furnace becomes a claim again; if the claim is gone the marker is cleared.
        if (hasTrackedProduction(villager) && restoreTrackedClaim(level, villager)) {
            return startProductionTrip(level, villager, Trip.COLLECT);
        }
        if (collectionDue(level, villager, gameTime)) {
            return startProductionTrip(level, villager, Trip.COLLECT);
        }
        if (askForFurnace(level, villager, gameTime)) {
            return startProductionTrip(level, villager, Trip.LOAD);
        }
        if (prepareJobSiteReturn(level, villager)) {
            return true;
        }
        if (gameTime < nextSearchTick) {
            return false;
        }
        return searchForTree(level, villager);
    }

    private boolean searchForTree(ServerLevel level, Villager villager) {
        ensureScanner(villager);
        // A tree beside the lumberjack should not wait behind the bounded world scan. This probe
        // is deliberately small; the full resumable scanner stays authoritative farther away.
        LocalTreeProbeResult localTreeResult = PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.LUMBERJACK_LOCAL_TREE_PROBE,
                () -> probeLocallyVisibleTrees(level, villager));
        if (localTreeResult == LocalTreeProbeResult.SELECTED) {
            treeScanner.resetSearch();
            searchRange = LumberjackTreeScanner.INITIAL_SEARCH_RANGE;
            nextSearchTick = level.getGameTime() + SEARCH_INTERVAL_TICKS;
            return true;
        }
        if (localTreeResult == LocalTreeProbeResult.RESERVED) {
            nextSearchTick = level.getGameTime() + LOCAL_RESERVED_RETRY_INTERVAL_TICKS;
            return false;
        }

        if (selectTrackedSaplingTree(level, villager)) {
            treeScanner.resetSearch();
            searchRange = LumberjackTreeScanner.INITIAL_SEARCH_RANGE;
            nextSearchTick = level.getGameTime() + SEARCH_INTERVAL_TICKS;
            return true;
        }

        List<LumberjackTreeScanner.TreeSnapshot> candidates = PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.LUMBERJACK_SEARCH,
                () -> treeScanner.findCandidateTrees(level, searchRange));
        if (!treeScanner.isSearchComplete()) {
            // Discovery is resumable and shares a per-level budget; advance it on the next pass.
            nextSearchTick = level.getGameTime() + SEARCH_SLICE_INTERVAL_TICKS;
            return false;
        }
        if (selectTreeFromCandidates(level, villager, candidates)) {
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

    @Override
    protected void start(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        outcome = Outcome.RUNNING;
        navigationWatchdog.reset();
        navigationFailures = 0;
        BlockPos target = currentTarget();
        if (target != null) {
            clearLeafBlockingApproach(level, villager, target);
        }
    }

    // ------------------------------------------------------------------ continue / stop

    @Override
    protected boolean canStillUse(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        // The Brain does not stop a running behavior when its activity ends (panic, raid, rest),
        // so the behavior ends itself; see the activity rules in the navigation plan.
        if (outcome != Outcome.RUNNING || !isBaseEligible(villager)
                || !villager.getBrain().isActive(Activity.WORK)) {
            return false;
        }
        if (!returningToJobSite && tree == null && trip == Trip.NONE) {
            return false;
        }
        if (!isEligible(level, villager, findVillageBankCached(level, villager))) {
            return false;
        }
        // Another behavior replaced the target this behavior published: yield to it.
        return intent.observe(villager, gameTime) != BrainMovementIntent.Status.REPLACED;
    }

    @Override
    protected void stop(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        intent.release(villager);
        clearBreakingProgress(level, villager);
        releaseTreeReservation(level, villager);
        if (logsCollectedThisTree > 0) {
            allocateCharcoalQuota(level, villager, logsCollectedThisTree);
        }
        tree = null;
        navigationTarget = null;
        reservedWorkPosition = null;
        jobSiteTarget = null;
        returningToJobSite = false;
        trip = Trip.NONE;
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
        outcome = Outcome.RUNNING;
    }

    // ------------------------------------------------------------------ tick

    @Override
    protected void tick(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        if (waitsForAnotherMover(villager)) {
            return;
        }
        if (returningToJobSite) {
            tickReturnToJobSite(level, villager, gameTime);
            return;
        }
        if (trip != Trip.NONE) {
            tickProductionTrip(level, villager, gameTime);
            return;
        }
        if (tree == null) {
            return;
        }

        BlockPos target = currentTarget();
        if (target == null) {
            finishTree(level, villager, gameTime);
            return;
        }

        if (clearLeafBlockingApproach(level, villager, target)) {
            // The old route may have ended at the leaf just removed; replan from here.
            navigationTarget = null;
            intent.release(villager);
            navigationWatchdog.reset();
            navigationFailures = 0;
        }
        if (!isWithinWorkReach(villager, target)) {
            travel(level, villager, gameTime, () -> moveToCurrentTarget(level, villager, gameTime));
            return;
        }

        // At a log: hold the position. The target is released so nothing else sees our walk.
        holdPositionWhileWorking(villager);

        BlockState state = getLoadedBlockState(level, target);
        if (!treeScanner.isLumberjackLog(state)) {
            clearBreakingProgress(level, villager);
            // The selected tree changed outside this task. Abandon the stale snapshot instead of
            // walking the remaining positions and replanting over another actor's work.
            releaseTreeReservation(level, villager);
            tree = null;
            navigationTarget = null;
            nextLogIndex = 0;
            breakTicks = 0;
            logsCollectedThisTree = 0;
            nextSearchTick = gameTime + SEARCH_INTERVAL_TICKS;
            return;
        }

        // A full inventory stops tree work: no log is broken when there is no room, so nothing
        // spills on the ground. The inventory-bank trip empties the inventory; check again soon.
        if (!canAccept(villager, new ItemStack(state.getBlock().asItem()))) {
            clearBreakingProgress(level, villager);
            if (gameTime >= nextInventoryRecheckTick) {
                nextInventoryRecheckTick = gameTime + FULL_INVENTORY_RECHECK_TICKS;
            }
            return;
        }

        if (breakingPos == null || !breakingPos.equals(target)) {
            clearBreakingProgress(level, villager);
            breakingPos = target.immutable();
        }

        breakTicks++;
        int requiredBreakTicks = handBreakTicks(level, target, state);
        updateBreakingProgress(level, villager, target, requiredBreakTicks);
        if (breakTicks >= requiredBreakTicks) {
            breakTicks = 0;
            clearBreakingProgress(level, villager);
            logsCollectedThisTree += breakBlockAndCollect(level, villager, target, state);
            nextLogIndex++;
            navigationTarget = null;
            navigationWatchdog.reset();
            navigationFailures = 0;
        }
    }

    /**
     * Another behavior (panic, boat, wanted item, the inventory-bank trip) owns the walk target:
     * wait and walk back after it ends instead of alternating the target with it.
     */
    private boolean waitsForAnotherMover(Villager villager) {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && !intent.ownsWalkTarget(villager);
    }

    /** Shared journey handling for tree and furnace travel: one publish, then observe. */
    private void travel(ServerLevel level, Villager villager, long gameTime, Runnable publish) {
        switch (intent.observe(villager, gameTime)) {
            case MOVING -> {
            }
            case UNREACHABLE, TIMED_OUT -> {
                intent.release(villager);
                navigationTarget = null;
                navigationWatchdog.reset();
                recordNavigationFailure(level, villager, gameTime);
            }
            default -> {
                // Nothing published, arrived short of reach, or the route ended: publish again,
                // but never more often than the retry interval.
                if (gameTime >= nextNavigationRetryTick) {
                    if (intent.destination() != null) {
                        intent.release(villager);
                        navigationTarget = null;
                    }
                    publish.run();
                }
            }
        }
    }

    private void holdPositionWhileWorking(Villager villager) {
        intent.release(villager);
        navigationTarget = null;
        navigationFailures = 0;
        navigationWatchdog.reset();
    }

    private boolean isWithinWorkReach(Villager villager, BlockPos target) {
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

    // ------------------------------------------------------------------ eligibility

    private static boolean isBaseEligible(Villager villager) {
        return villager.getVillagerData().getProfession() == ECAPVillagerProfessions.LUMBERJACK.get()
                && !villager.isBaby()
                && !villager.isSleeping()
                && !villager.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager);
    }

    private boolean isEligible(ServerLevel level, Villager villager, @Nullable BankBlockEntity bank) {
        return (lumberjackCuttingEnabled(bank) || hasTrackedProduction(villager))
                && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    private static boolean lumberjackCuttingEnabled(@Nullable BankBlockEntity bank) {
        return bank == null || (bank.isVillagerDeliveriesEnabled()
                && bank.isLumberjackDeliveriesEnabled());
    }

    @Nullable
    private BankBlockEntity findVillageBankCached(ServerLevel level, Villager villager) {
        long gameTime = level.getGameTime();
        if (gameTime < nextBankLookupTick
                && cachedBankLevel == level
                && (cachedVillageBank == null || !cachedVillageBank.isRemoved())) {
            return cachedVillageBank;
        }
        cachedBankLevel = level;
        nextBankLookupTick = gameTime + BANK_LOOKUP_INTERVAL_TICKS;
        cachedVillageBank = BankEmployeeLookup.findContainingVillageBank(level, villager);
        return cachedVillageBank;
    }

    private void ensureScanner(Villager villager) {
        if (treeScanner == null) {
            treeScanner = new LumberjackTreeScanner(villager);
        }
    }

    // ------------------------------------------------------------------ tree selection

    private boolean selectTrackedSaplingTree(ServerLevel level, Villager villager) {
        List<LumberjackSaplingCache.Candidate> grownSaplings = LumberjackSaplingCache.findGrownSaplings(
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
            if (selectTreeFromCandidates(level, villager, List.of(candidate))) {
                return true;
            }
        }
        return false;
    }

    private LocalTreeProbeResult probeLocallyVisibleTrees(ServerLevel level, Villager villager) {
        BlockPos origin = villager.blockPosition();
        boolean reservedTreeFound = false;
        int positionsToCheck = LumberjackTreeScanner.claimScanBudget(level, LOCAL_TREE_PROBE_MAX_POSITIONS);
        Set<BlockPos> examinedLogs = new HashSet<>();
        BlockPos.MutableBlockPos candidatePos = new BlockPos.MutableBlockPos();
        for (int dx = -LOCAL_TREE_PROBE_HORIZONTAL_RANGE; dx <= LOCAL_TREE_PROBE_HORIZONTAL_RANGE; dx++) {
            for (int dy = -LOCAL_TREE_PROBE_VERTICAL_RANGE; dy <= LOCAL_TREE_PROBE_VERTICAL_RANGE; dy++) {
                for (int dz = -LOCAL_TREE_PROBE_HORIZONTAL_RANGE; dz <= LOCAL_TREE_PROBE_HORIZONTAL_RANGE; dz++) {
                    if (positionsToCheck-- <= 0) {
                        return reservedTreeFound ? LocalTreeProbeResult.RESERVED : LocalTreeProbeResult.NONE;
                    }
                    candidatePos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = getLoadedBlockState(level, candidatePos);
                    if (!treeScanner.isLumberjackLog(state)) {
                        continue;
                    }
                    LumberjackTreeScanner.TreeSnapshot candidate =
                            treeScanner.findCandidateTreeAt(level, candidatePos, examinedLogs);
                    if (candidate == null) {
                        continue;
                    }
                    if (LumberjackTreeReservations.isReservedByOther(
                            level, villager.getUUID(), candidate.logs())) {
                        reservedTreeFound = true;
                        continue;
                    }
                    if (selectTreeFromCandidates(level, villager, List.of(candidate))) {
                        return LocalTreeProbeResult.SELECTED;
                    }
                }
            }
        }
        return reservedTreeFound ? LocalTreeProbeResult.RESERVED : LocalTreeProbeResult.NONE;
    }

    private enum LocalTreeProbeResult {
        SELECTED,
        RESERVED,
        NONE
    }

    private int nextSearchRange() {
        if (searchRange >= LumberjackTreeScanner.MAX_SEARCH_RANGE) {
            return LumberjackTreeScanner.INITIAL_SEARCH_RANGE;
        }
        return Math.min(LumberjackTreeScanner.MAX_SEARCH_RANGE,
                searchRange + LumberjackTreeScanner.SEARCH_RANGE_INCREMENT);
    }

    private boolean selectTreeFromCandidates(ServerLevel level, Villager villager,
                                             List<LumberjackTreeScanner.TreeSnapshot> candidates) {
        candidateTrees = candidates;
        nextCandidateIndex = 0;
        return selectNextTree(level, villager);
    }

    private boolean selectNextTree(ServerLevel level, Villager villager) {
        while (nextCandidateIndex < candidateTrees.size()) {
            LumberjackTreeScanner.TreeSnapshot candidate = candidateTrees.get(nextCandidateIndex++);
            if (LumberjackTreeReservations.isReservedByOther(level, villager.getUUID(), candidate.logs())) {
                continue;
            }
            BlockPos approach = findReachableTreeApproach(level, villager, candidate);
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
            outcome = Outcome.RUNNING;
            return true;
        }
        tree = null;
        navigationTarget = null;
        reservedWorkPosition = null;
        return false;
    }

    @Nullable
    private BlockPos findReachableTreeApproach(ServerLevel level, Villager villager,
                                               LumberjackTreeScanner.TreeSnapshot candidate) {
        // A nearby tree is already within cutting reach; do not require a path to the trunk.
        if (isWithinWorkReach(villager, candidate.base())) {
            return villager.blockPosition().immutable();
        }
        BlockPos approach = VillagerNavigationTargets.findReachableTargetOrFallback(
                villager, candidate.base(), 2, position -> isPathfindable(level, position));
        if (approach != null) {
            return approach;
        }
        int maxIndex = Math.min(candidate.logs().size(), MAX_UPPER_TRUNK_APPROACHES + 1);
        for (int index = 1; index < maxIndex; index++) {
            approach = VillagerNavigationTargets.findReachableTargetOrFallback(
                    villager, candidate.logs().get(index), 2, position -> isPathfindable(level, position));
            if (approach != null) {
                return approach;
            }
        }
        return null;
    }

    private static boolean isPathfindable(ServerLevel level, BlockPos position) {
        BlockState state = getLoadedBlockState(level, position);
        return state != null && state.isPathfindable(PathComputationType.LAND);
    }

    private boolean selectNextTreeAfterNavigationFailure(ServerLevel level, Villager villager) {
        clearBreakingProgress(level, villager);
        allocateCharcoalQuota(level, villager, logsCollectedThisTree);
        releaseTreeReservation(level, villager);
        tree = null;
        navigationTarget = null;
        nextLogIndex = 0;
        breakTicks = 0;
        logsCollectedThisTree = 0;
        navigationWatchdog.reset();
        return selectNextTree(level, villager);
    }

    @Nullable
    private BlockPos currentTarget() {
        if (tree == null || nextLogIndex >= tree.logs().size()) {
            return null;
        }
        return tree.logs().get(nextLogIndex);
    }

    private void moveToCurrentTarget(ServerLevel level, Villager villager, long gameTime) {
        BlockPos target = currentTarget();
        if (target == null) {
            return;
        }
        if (navigationTarget == null) {
            navigationTarget = VillagerNavigationTargets.findReachableTargetOrFallback(
                    villager, target, 2, candidate -> isPathfindable(level, candidate));
            if (navigationTarget == null) {
                recordNavigationFailure(level, villager, gameTime);
                return;
            }
        }
        intent.walkTo(villager, navigationTarget, SPEED_MODIFIER, CLOSE_ENOUGH, gameTime);
        nextNavigationRetryTick = gameTime + 20L;
    }

    private void recordNavigationFailure(ServerLevel level, Villager villager, long gameTime) {
        navigationFailures++;
        nextNavigationRetryTick = gameTime + NAVIGATION_RETRY_INTERVAL_TICKS;
        if (navigationFailures >= MAX_NAVIGATION_FAILURES) {
            navigationFailures = 0;
            if (trip != Trip.NONE) {
                // A furnace that cannot be reached: keep the claim, stop the trip, try later.
                outcome = Outcome.PRODUCTION_BLOCKED;
                nextCollectCheckTick = gameTime + PRODUCTION_ASK_INTERVAL_TICKS;
                nextProductionAskTick = gameTime + PRODUCTION_ASK_INTERVAL_TICKS;
                return;
            }
            if (returningToJobSite) {
                outcome = Outcome.JOB_SITE_UNREACHABLE;
                return;
            }
            if (!selectNextTreeAfterNavigationFailure(level, villager)) {
                searchRange = nextSearchRange();
                nextSearchTick = gameTime + EMPTY_SEARCH_INTERVAL_TICKS;
                outcome = Outcome.NO_REACHABLE_TREE;
            }
        }
    }

    /**
     * Removes selected-tree leaves occupying the villager's forward head space. Leaving part of
     * that space blocked can prevent a route from reaching the next log.
     */
    private boolean clearLeafBlockingApproach(ServerLevel level, Villager villager, BlockPos target) {
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
                    if ((targetX != 0 || targetZ != 0) && dx * targetX + dz * targetZ <= 0) {
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
                    breakBlockAndCollect(level, villager, candidate, state);
                    cleared = true;
                }
            }
        }
        return cleared;
    }

    // ------------------------------------------------------------------ job site

    private boolean prepareJobSiteReturn(ServerLevel level, Villager villager) {
        long gameTime = level.getGameTime();
        BlockPos jobSite = getJobSite(level, villager);
        if (jobSite == null || isWithinWorkReach(villager, jobSite)) {
            nextJobSiteReturnAttemptTick = gameTime;
            return false;
        }
        if (gameTime < nextJobSiteReturnAttemptTick) {
            return false;
        }
        jobSiteTarget = VillagerNavigationTargets.findReachableTargetOrFallback(villager, jobSite, 2);
        nextJobSiteReturnAttemptTick = gameTime + JOB_SITE_SEARCH_RETRY_INTERVAL_TICKS;
        if (jobSiteTarget == null) {
            // A stale or obstructed job site must not stop the lumberjack scanning from here.
            return false;
        }
        returningToJobSite = true;
        navigationTarget = null;
        outcome = Outcome.RUNNING;
        return true;
    }

    private void tickReturnToJobSite(ServerLevel level, Villager villager, long gameTime) {
        BlockPos jobSite = getJobSite(level, villager);
        if (jobSite == null || isWithinWorkReach(villager, jobSite)) {
            holdPositionWhileWorking(villager);
            jobSiteTarget = null;
            returningToJobSite = false;
            nextSearchTick = gameTime;
            return;
        }
        travel(level, villager, gameTime, () -> {
            if (jobSiteTarget == null) {
                jobSiteTarget = VillagerNavigationTargets.findReachableTargetOrFallback(villager, jobSite, 2);
            }
            if (jobSiteTarget == null) {
                outcome = Outcome.JOB_SITE_UNREACHABLE;
                return;
            }
            intent.walkTo(villager, jobSiteTarget, SPEED_MODIFIER, CLOSE_ENOUGH, gameTime);
            nextNavigationRetryTick = gameTime + 20L;
        });
    }

    @Nullable
    private static BlockPos getJobSite(ServerLevel level, Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(globalPos -> globalPos.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .filter(pos -> {
                    BlockState state = getLoadedBlockState(level, pos);
                    return state != null && state.is(ECAPBlocks.SAWMILL.get());
                })
                .orElse(null);
    }

    // ------------------------------------------------------------------ cutting

    private int handBreakTicks(ServerLevel level, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(hardness * HAND_BREAK_TICKS_PER_HARDNESS));
    }

    /** Publishes the vanilla 0-9 block-crack stages; the level sends the packet to clients. */
    private void updateBreakingProgress(ServerLevel level, Villager villager, BlockPos pos,
                                        int requiredBreakTicks) {
        int stage = Math.min(9, (int) ((long) breakTicks * 10L / requiredBreakTicks));
        if (stage != lastBreakingStage) {
            level.destroyBlockProgress(villager.getId(), pos, stage);
            lastBreakingStage = stage;
        }
    }

    private void clearBreakingProgress(ServerLevel level, Villager villager) {
        if (breakingPos != null) {
            level.destroyBlockProgress(villager.getId(), breakingPos, -1);
        }
        breakingPos = null;
        lastBreakingStage = -1;
    }

    private int breakBlockAndCollect(ServerLevel level, Villager villager, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, villager, ItemStack.EMPTY);
        if (level.destroyBlock(pos, false, villager)) {
            int collectedLogs = collectDrops(level, villager, drops);
            // Vanilla clients read event 2001 as the block's break sound plus its debris particles.
            level.levelEvent(2001, pos, Block.getId(state));
            return collectedLogs;
        }
        return 0;
    }

    private void finishTree(ServerLevel level, Villager villager, long gameTime) {
        if (tree == null) {
            return;
        }
        clearBreakingProgress(level, villager);
        for (BlockPos leafPos : tree.leaves()) {
            BlockState leafState = getLoadedBlockState(level, leafPos);
            if (leafState != null && leafState.is(BlockTags.LEAVES)) {
                List<ItemStack> drops = Block.getDrops(leafState, level, leafPos, null, villager, ItemStack.EMPTY);
                if (level.destroyBlock(leafPos, false, villager)) {
                    collectDrops(level, villager, drops);
                }
            }
        }
        for (BlockPos log : tree.logs()) {
            LumberjackSaplingCache.forget(level, villager.getUUID(), log);
        }
        replant(level, villager, tree.base(), tree.sapling());
        allocateCharcoalQuota(level, villager, logsCollectedThisTree);
        releaseTreeReservation(level, villager);
        tree = null;
        nextLogIndex = 0;
        breakTicks = 0;
        logsCollectedThisTree = 0;
        // A tree is finished: a stage boundary, where production may start or be collected.
        if (collectionDue(level, villager, gameTime)) {
            startProductionTrip(level, villager, Trip.COLLECT);
        } else if (askForFurnace(level, villager, gameTime)) {
            startProductionTrip(level, villager, Trip.LOAD);
        }
    }

    private int collectDrops(ServerLevel level, Villager villager, List<ItemStack> drops) {
        int collectedLogs = 0;
        for (ItemStack drop : drops) {
            ItemStack offered = drop.copy();
            if (!com.orangevillager61.emeraldcapitalism.util.VillagerPickupCompat.wants(villager, level, offered)) {
                com.orangevillager61.emeraldcapitalism.util.EntityDropUtils.spawn(villager, level, offered);
                continue;
            }
            int offeredLogCount = offered.is(ItemTags.LOGS) ? offered.getCount() : 0;
            ItemStack remainder = villager.getInventory().addItem(offered);
            if (!remainder.isEmpty()) {
                com.orangevillager61.emeraldcapitalism.util.EntityDropUtils.spawn(villager, level, remainder);
            }
            if (offeredLogCount > 0) {
                collectedLogs += offeredLogCount - remainder.getCount();
            }
        }
        return collectedLogs;
    }

    private void replant(ServerLevel level, Villager villager, BlockPos base, Item preferredSapling) {
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

    private void releaseTreeReservation(ServerLevel level, Villager villager) {
        if (tree != null) {
            LumberjackTreeReservations.release(level, villager.getUUID(), tree.logs(), reservedWorkPosition);
        }
        reservedWorkPosition = null;
    }

    // ------------------------------------------------------------------ charcoal quota

    /**
     * Adds only the logs actually inserted into the inventory to the persistent quota. A village
     * with no pool furnace accrues none (and, once its pool has been read, owes none); while the
     * lumberjack waits for a furnace the quota stops growing at two batches.
     */
    private void allocateCharcoalQuota(ServerLevel level, Villager villager, int newlyCollectedLogs) {
        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        VillageRecord village = villageOf(level, villager);
        if (village == null || village.getFurnacePool(level.getGameTime()).furnaces().isEmpty()) {
            if (village != null && village.hasReconciledFurnacePool()) {
                production.setCharcoalQuota(0.0D);
            }
            return;
        }
        if (newlyCollectedLogs <= 0) {
            return;
        }
        double share = charcoalShare(level, villager);
        boolean waiting = level.getGameTime() - lastWaitingForFurnaceTick <= PRODUCTION_ASK_INTERVAL_TICKS * 2L;
        double quota = waiting
                ? CharcoalProductionPolicy.accrueQuotaWhileWaiting(
                production.getCharcoalQuota(), newlyCollectedLogs, share)
                : CharcoalProductionPolicy.accrueQuota(production.getCharcoalQuota(), newlyCollectedLogs, share);
        production.setCharcoalQuota(quota);
    }

    private double charcoalShare(ServerLevel level, Villager villager) {
        BankBlockEntity bank = findVillageBankCached(level, villager);
        if (bank == null) {
            return CharcoalProductionPolicy.DEFAULT_SHARE;
        }
        MarketItem logs = MarketRegistry.get("oak_log").orElse(null);
        MarketItem charcoal = MarketRegistry.get("coal").orElse(null);
        if (logs == null || charcoal == null) {
            return CharcoalProductionPolicy.DEFAULT_SHARE;
        }
        int population = bank.getMarketPopulation(level);
        MarketDemandContext logContext = new MarketDemandContext(population, bank.getMarketTarget(level, logs));
        MarketDemandContext charcoalContext = new MarketDemandContext(
                population, bank.getMarketTarget(level, charcoal));
        double logRate = MarketPricingEngine.midRate(
                logs.config(), bank.getMarketStock(level, logs.item()), logContext);
        double charcoalRate = MarketPricingEngine.midRate(
                charcoal.config(), bank.getMarketStock(level, charcoal.item()), charcoalContext);
        return CharcoalProductionPolicy.charcoalShare(logRate, charcoalRate);
    }

    // ------------------------------------------------------------------ furnace production

    @Nullable
    private static VillageRecord villageOf(ServerLevel level, Villager villager) {
        VillageRecord village = VillageRegistryData.get(level).getVillageFor(villager.blockPosition());
        return village != null && village.hasMember(villager.getUUID()) ? village : null;
    }

    private static boolean hasTrackedProduction(Villager villager) {
        return villager.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .getPendingCharcoalFurnace().isPresent();
    }

    private static void clearTrackedProduction(Villager villager) {
        villager.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).setPendingCharcoalFurnace(null);
    }

    @Nullable
    private FurnacePool poolOf(ServerLevel level, Villager villager) {
        VillageRecord village = villageOf(level, villager);
        return village == null ? null : village.getFurnacePool(level.getGameTime());
    }

    @Nullable
    private FurnacePool.Grant currentGrant(ServerLevel level, Villager villager) {
        FurnacePool pool = poolOf(level, villager);
        return pool == null ? null : pool.activeGrant(villager.getUUID()).orElse(null);
    }

    /** The saved furnace becomes a claim again; if the claim is gone the saved marker is cleared. */
    private boolean restoreTrackedClaim(ServerLevel level, Villager villager) {
        if (currentGrant(level, villager) != null) {
            return true;
        }
        GlobalPos tracked = villager.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .getPendingCharcoalFurnace().orElse(null);
        if (tracked == null || !tracked.dimension().equals(level.dimension())) {
            return false;
        }
        BlockPos pos = tracked.pos();
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        FurnacePool pool = poolOf(level, villager);
        if (pool == null || !(BankEmployeeLookup.getLoadedBlockEntity(level, pos) instanceof FurnaceBlockEntity furnace)) {
            clearTrackedProduction(villager);
            return false;
        }
        if (pool.restoreClaim(villager.getUUID(), pos.asLong(), observe(furnace)).isPresent()) {
            return true;
        }
        clearTrackedProduction(villager);
        return false;
    }

    /** True once a batch loaded earlier is expected to be ready and the holder is at a stage boundary. */
    private boolean collectionDue(ServerLevel level, Villager villager, long gameTime) {
        FurnacePool.Grant grant = currentGrant(level, villager);
        return grant != null && grant.batch().logsLoaded() > 0 && gameTime >= nextCollectCheckTick;
    }

    /**
     * Asks the village for a furnace, only with a full batch of quota logs in hand. A refusal
     * leaves the lumberjack cutting; it never walks to a furnace it was not given.
     */
    private boolean askForFurnace(ServerLevel level, Villager villager, long gameTime) {
        if (gameTime < nextProductionAskTick || currentGrant(level, villager) != null || !hasReadyBatch(villager)) {
            return false;
        }
        nextProductionAskTick = gameTime + PRODUCTION_ASK_INTERVAL_TICKS;
        FurnacePool pool = poolOf(level, villager);
        if (pool == null || pool.furnaces().isEmpty() || !canStartProduction(villager)) {
            return false;
        }
        if (pool.request(villager.getUUID(), gameTime).isPresent()) {
            return true;
        }
        lastWaitingForFurnaceTick = gameTime;
        return false;
    }

    private boolean startProductionTrip(ServerLevel level, Villager villager, Trip kind) {
        FurnacePool.Grant grant = currentGrant(level, villager);
        if (grant == null) {
            return false;
        }
        trip = kind;
        navigationTarget = null;
        outcome = Outcome.RUNNING;
        navigationWatchdog.reset();
        return true;
    }

    private void tickProductionTrip(ServerLevel level, Villager villager, long gameTime) {
        FurnacePool.Grant grant = currentGrant(level, villager);
        FurnacePool pool = poolOf(level, villager);
        if (grant == null || pool == null) {
            // The claim is gone (taken over by the village or the furnace was removed).
            clearTrackedProduction(villager);
            intent.release(villager);
            trip = Trip.NONE;
            return;
        }
        BlockPos furnacePos = BlockPos.of(grant.position());
        if (!level.hasChunk(furnacePos.getX() >> 4, furnacePos.getZ() >> 4)) {
            trip = Trip.NONE;
            return;
        }
        if (!(BankEmployeeLookup.getLoadedBlockEntity(level, furnacePos) instanceof FurnaceBlockEntity furnace)) {
            // A removed furnace drops its claim; the owner rejoins the queue.
            VillageRecord village = villageOf(level, villager);
            if (village != null) {
                village.onFurnaceRemoved(level, furnacePos);
            }
            clearTrackedProduction(villager);
            intent.release(villager);
            trip = Trip.NONE;
            return;
        }
        if (!isWithinWorkReach(villager, furnacePos)) {
            travel(level, villager, gameTime, () -> moveToFurnace(villager, furnacePos, gameTime));
            return;
        }
        holdPositionWhileWorking(villager);
        if (trip == Trip.LOAD) {
            loadFurnace(level, villager, pool, grant, furnace, furnacePos, gameTime);
        } else {
            collectFromFurnace(level, villager, pool, grant, furnace, gameTime);
        }
    }

    private void moveToFurnace(Villager villager, BlockPos furnacePos, long gameTime) {
        if (navigationTarget == null) {
            navigationTarget = VillagerNavigationTargets.findReachableTargetOrFallback(villager, furnacePos, 2);
            if (navigationTarget == null) {
                recordNavigationFailure((ServerLevel) villager.level(), villager, gameTime);
                return;
            }
        }
        intent.walkTo(villager, navigationTarget, SPEED_MODIFIER, CLOSE_ENOUGH, gameTime);
        nextNavigationRetryTick = gameTime + 20L;
    }

    /**
     * Loads one batch. With charcoal in hand the fuel is one charcoal and up to eight logs go in.
     * Without charcoal this is the seed batch: one log becomes four planks of fuel and one log
     * is smelted; its charcoal stays in the inventory as fuel for every later batch.
     */
    private void loadFurnace(ServerLevel level, Villager villager, FurnacePool pool,
                             FurnacePool.Grant grant, FurnaceBlockEntity furnace, BlockPos furnacePos,
                             long gameTime) {
        trip = Trip.NONE;
        FurnaceClaim.Observation observed = observe(furnace);
        if (grant.batch().logsLoaded() > 0) {
            // Already loaded earlier (reacquired after a stop): go on to collecting later.
            return;
        }
        if (observed.foreign() || observed.inputLogs() > 0 || observed.outputCharcoal() > 0) {
            // Someone else's items: never overwrite, never take. Release and mark it occupied.
            pool.markOccupied(grant.position());
            pool.release(grant.claim());
            outcome = Outcome.PRODUCTION_BLOCKED;
            return;
        }
        if (!hasReadyBatch(villager) || !canAccept(villager, new ItemStack(Items.CHARCOAL))) {
            pool.release(grant.claim());
            return;
        }

        boolean seed = false;
        int fuelPlaced = 0;
        ItemStack fuel = furnace.getItem(FURNACE_FUEL_SLOT);
        if (fuel.isEmpty()) {
            if (removeOneItem(villager, Items.CHARCOAL)) {
                fuel = new ItemStack(Items.CHARCOAL);
                fuelPlaced = 1;
            } else {
                Item fuelLog = findCompatibleLogForPlanks(villager);
                if (fuelLog == null || countLogsInInventory(villager) <= CharcoalProductionPolicy.MIN_CHARCOAL_BATCH_LOGS) {
                    // Keep the quota and go back to cutting rather than waiting at a furnace
                    // that cannot be fuelled.
                    pool.release(grant.claim());
                    return;
                }
                removeOneItem(villager, fuelLog);
                fuel = new ItemStack(planksForLog(fuelLog), PLANKS_PER_LOG);
                fuelPlaced = PLANKS_PER_LOG;
                seed = true;
            }
            furnace.setItem(FURNACE_FUEL_SLOT, fuel);
        } else if (!isPoolFuel(fuel)) {
            pool.markOccupied(grant.position());
            pool.release(grant.claim());
            outcome = Outcome.PRODUCTION_BLOCKED;
            return;
        }

        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        int wanted = seed ? 1 : Math.min(FUEL_ITEMS_PER_CHARCOAL,
                CharcoalProductionPolicy.wholeConversions(production.getCharcoalQuota(), countLogsInInventory(villager)));
        Item logItem = firstLogItem(villager);
        int loaded = 0;
        while (logItem != null && loaded < wanted && removeOneItem(villager, logItem)) {
            loaded++;
        }
        if (loaded <= 0) {
            if (fuelPlaced > 0) {
                furnace.setItem(FURNACE_FUEL_SLOT, ItemStack.EMPTY);
                returnFuel(villager, fuel.getItem(), fuelPlaced);
            }
            pool.release(grant.claim());
            return;
        }
        furnace.setItem(FURNACE_INPUT_SLOT, new ItemStack(logItem, loaded));
        furnace.setChanged();
        // Charge the quota when the logs enter the furnace: a stop before collection must not
        // let an in-flight conversion be forgotten and counted again.
        production.setCharcoalQuota(CharcoalProductionPolicy.afterConversions(production.getCharcoalQuota(), loaded));
        production.setPendingCharcoalFurnace(GlobalPos.of(level.dimension(), furnacePos));
        pool.recordLoaded(grant.claim(), loaded, gameTime);
        if (fuelPlaced > 0) {
            grant.batch().recordFuel(fuelPlaced);
        }
        grant.batch().beginSmelting();
        nextCollectCheckTick = gameTime + FurnacePool.SMELT_TICKS_PER_ITEM * loaded;
    }

    private void collectFromFurnace(ServerLevel level, Villager villager, FurnacePool pool,
                                    FurnacePool.Grant grant, FurnaceBlockEntity furnace, long gameTime) {
        trip = Trip.NONE;
        FurnaceClaim.Observation observed = observe(furnace);
        Optional<FurnacePool.CheckResult> checked = pool.check(grant.claim(), observed);
        if (checked.isEmpty()) {
            clearTrackedProduction(villager);
            return;
        }
        FurnaceClaim.Reconciliation result = checked.get().reconciliation();
        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        if (result.refundedConversions() > 0) {
            // A log was removed before it smelted; the charcoal is still owed.
            production.setCharcoalQuota(CharcoalProductionPolicy.accrueQuota(
                    production.getCharcoalQuota(), result.refundedConversions(), 1.0D));
        }
        int taken = 0;
        ItemStack output = furnace.getItem(FURNACE_RESULT_SLOT);
        if (result.takeCharcoal() > 0 && output.is(Items.CHARCOAL)) {
            int wanted = Math.min(result.takeCharcoal(), output.getCount());
            ItemStack offered = new ItemStack(Items.CHARCOAL, wanted);
            if (canAccept(villager, offered)) {
                ItemStack remainder = villager.getInventory().addItem(offered);
                taken = wanted - remainder.getCount();
            }
            if (taken > 0) {
                output.shrink(taken);
                furnace.setItem(FURNACE_RESULT_SLOT, output.isEmpty() ? ItemStack.EMPTY : output);
                furnace.setChanged();
                grant.batch().recordCollected(taken);
            }
        }
        if (checked.get().released()) {
            clearTrackedProduction(villager);
            nextProductionAskTick = gameTime;
            return;
        }
        if (grant.batch().charcoalOutstanding() == 0) {
            // Everything owed was collected or accounted for; verify the furnace empty next.
            nextCollectCheckTick = gameTime + FULL_INVENTORY_RECHECK_TICKS;
        } else {
            nextCollectCheckTick = gameTime + (taken > 0 ? FurnacePool.SMELT_TICKS_PER_ITEM
                    : PRODUCTION_ASK_INTERVAL_TICKS);
        }
    }

    private static FurnaceClaim.Observation observe(FurnaceBlockEntity furnace) {
        ItemStack input = furnace.getItem(FURNACE_INPUT_SLOT);
        ItemStack output = furnace.getItem(FURNACE_RESULT_SLOT);
        ItemStack fuel = furnace.getItem(FURNACE_FUEL_SLOT);
        boolean foreignInput = !input.isEmpty() && !input.is(ItemTags.LOGS);
        boolean foreignOutput = !output.isEmpty() && !output.is(Items.CHARCOAL);
        boolean foreignFuel = !fuel.isEmpty() && !isPoolFuel(fuel);
        return new FurnaceClaim.Observation(
                input.is(ItemTags.LOGS) ? input.getCount() : 0,
                output.is(Items.CHARCOAL) ? output.getCount() : 0,
                foreignInput, foreignOutput, foreignFuel);
    }

    /** The only fuel the pool ever places: charcoal, or planks made from a log of the batch. */
    private static boolean isPoolFuel(ItemStack stack) {
        return stack.is(Items.CHARCOAL) || stack.is(ItemTags.PLANKS);
    }

    private boolean hasReadyBatch(Villager villager) {
        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        return CharcoalProductionPolicy.readyBatchConversions(
                production.getCharcoalQuota(), countLogsInInventory(villager)) > 0;
    }

    /** An eight-log batch also needs fuel: charcoal in hand, or a log beyond the batch for planks. */
    private static boolean canStartProduction(Villager villager) {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            if (villager.getInventory().getItem(slot).is(Items.CHARCOAL)) {
                return true;
            }
        }
        return findCompatibleLogForPlanks(villager) != null
                && countLogsInInventory(villager) > CharcoalProductionPolicy.MIN_CHARCOAL_BATCH_LOGS;
    }

    // ------------------------------------------------------------------ inventory helpers

    private static int countLogsInInventory(Villager villager) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Nullable
    private static Item firstLogItem(Villager villager) {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS)) {
                return stack.getItem();
            }
        }
        return null;
    }

    private static boolean removeOneItem(Villager villager, Item item) {
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

    private static void returnFuel(Villager villager, Item fuelItem, int count) {
        if (fuelItem == Items.CHARCOAL) {
            villager.getInventory().addItem(new ItemStack(Items.CHARCOAL, count));
        } else {
            // Planks came from one log; give one log back.
            Item log = logForPlanks(fuelItem);
            if (log != null) {
                villager.getInventory().addItem(new ItemStack(log));
            }
        }
    }

    @Nullable
    private static Item findCompatibleLogForPlanks(Villager villager) {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS) && planksForLog(stack.getItem()) != null) {
                return stack.getItem();
            }
        }
        return null;
    }

    @Nullable
    private static Item logForPlanks(Item planks) {
        if (planks == Items.OAK_PLANKS) {
            return Items.OAK_LOG;
        }
        if (planks == Items.SPRUCE_PLANKS) {
            return Items.SPRUCE_LOG;
        }
        if (planks == Items.BIRCH_PLANKS) {
            return Items.BIRCH_LOG;
        }
        if (planks == Items.JUNGLE_PLANKS) {
            return Items.JUNGLE_LOG;
        }
        if (planks == Items.ACACIA_PLANKS) {
            return Items.ACACIA_LOG;
        }
        if (planks == Items.DARK_OAK_PLANKS) {
            return Items.DARK_OAK_LOG;
        }
        if (planks == Items.MANGROVE_PLANKS) {
            return Items.MANGROVE_LOG;
        }
        return null;
    }

    @Nullable
    private static Item planksForLog(Item log) {
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

    private static boolean canAccept(Villager villager, ItemStack incoming) {
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

    @Nullable
    private static BlockState getLoadedBlockState(ServerLevel level, BlockPos pos) {
        return BankEmployeeLookup.getLoadedBlockState(level, pos);
    }
}
