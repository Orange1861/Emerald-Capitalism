package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.Node;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Small server-side helpers for choosing a reachable standing target near a task block. */
public final class VillagerNavigationTargets {

    private static final int MAX_SEARCH_RADIUS = 8;
    private static final int MAX_PATH_ATTEMPTS = 16;
    private static final double INTERACTION_RANGE_SQ = 2.25D;

    private VillagerNavigationTargets() {
    }

    /**
     * Finds a path-reachable block near {@code desired}. The search is deliberately
     * bounded because callers use it only when a movement goal starts or retries.
     */
    @Nullable
    public static BlockPos findReachableTarget(Mob mob, BlockPos desired, int radius) {
        return findReachableTarget(mob, desired, radius, ignored -> true);
    }

    /** Finds a path-reachable block near {@code desired} that passes {@code acceptable}. */
    @Nullable
    public static BlockPos findReachableTarget(Mob mob, BlockPos desired, int radius,
                                                Predicate<BlockPos> acceptable) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(desired, "desired");
        Objects.requireNonNull(acceptable, "acceptable");
        if (radius < 0 || radius > MAX_SEARCH_RADIUS) {
            throw new IllegalArgumentException("Navigation target radius must be between 0 and "
                    + MAX_SEARCH_RADIUS + ": " + radius);
        }

        PathNavigation navigation = mob.getNavigation();
        if (radius == 0) {
            if (!acceptable.test(desired) || !isValidStandingSpace(mob, desired)) {
                return null;
            }
            Path exactPath = PerformanceTimingCounters.measurePathPlan(
                    PerformanceTimingCounters.Operation.MOVEMENT_HELPER_PATH_PLAN_ATTEMPT,
                    () -> navigation.createPath(desired, 0));
            if (exactPath != null) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_HELPER_PATH_OBJECT_CREATED);
            }
            return exactPath != null && exactPath.canReach()
                    && endsWithinInteractionRange(exactPath, desired)
                    ? desired.immutable() : null;
        }

        List<List<BlockPos>> candidatesByRing = new ArrayList<>(radius + 1);
        for (int ring = 0; ring <= radius; ring++) {
            candidatesByRing.add(new ArrayList<>());
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = desired.offset(dx, dy, dz);
                    int ring = Math.max(Math.abs(dx), Math.abs(dz));
                    if (acceptable.test(candidate)
                            && isValidStandingSpace(mob, candidate)) {
                        candidatesByRing.get(ring).add(candidate);
                    }
                }
            }
        }

        List<BlockPos> candidates = distributeAttempts(candidatesByRing, desired);

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos candidate : candidates) {
            double distancePenalty = candidate.distSqr(desired) * 100.0D;

            // Require the navigation result to arrive at the candidate itself;
            // a one-block createPath tolerance can otherwise return an
            // endpoint that is not usable by the interaction goal.
            Path path = PerformanceTimingCounters.measurePathPlan(
                    PerformanceTimingCounters.Operation.MOVEMENT_HELPER_PATH_PLAN_ATTEMPT,
                    () -> navigation.createPath(candidate, 0));
            if (path != null) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_HELPER_PATH_OBJECT_CREATED);
            }
            if (path == null || !path.canReach()
                    || !endsWithinInteractionRange(path, desired)) {
                continue;
            }

            // Prefer the task block and then shorter routes to avoid unnecessary
            // detours around an otherwise reachable workstation or crop.
            double score = distancePenalty + path.getNodeCount();
            if (score < bestScore) {
                best = candidate.immutable();
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Selects a reachable target when the current pathfinder snapshot has one,
     * otherwise returns a clear, supported interaction position for the real
     * MoveToTargetSink to plan on a later AI tick. The fallback is only a
     * candidate; callers must keep their normal arrival and failure checks.
     */
    @Nullable
    public static BlockPos findReachableTargetOrFallback(
            Mob mob, BlockPos desired, int radius, Predicate<BlockPos> acceptable) {
        BlockPos reachable = findReachableTarget(mob, desired, radius, acceptable);
        return reachable != null
                ? reachable
                : findStandingTargetCandidate(mob, desired, radius, acceptable);
    }

    public static BlockPos findReachableTargetOrFallback(Mob mob, BlockPos desired, int radius) {
        return findReachableTargetOrFallback(mob, desired, radius, ignored -> true);
    }

    @Nullable
    private static BlockPos findStandingTargetCandidate(
            Mob mob, BlockPos desired, int radius, Predicate<BlockPos> acceptable) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = desired.offset(dx, dy, dz);
                    if (!acceptable.test(candidate) || !isValidStandingSpace(mob, candidate)) {
                        continue;
                    }
                    double distance = candidate.distSqr(desired);
                    double score = distance * 100.0D + candidate.distSqr(mob.blockPosition());
                    if (score < bestScore) {
                        best = candidate.immutable();
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Gives every non-empty horizontal search ring an attempt before spending
     * the remaining budget. Sampling within each ring keeps the bounded search
     * from being consumed by the positions immediately beside the task block.
     */
    private static List<BlockPos> distributeAttempts(List<List<BlockPos>> candidatesByRing,
                                                       BlockPos desired) {
        int nonEmptyRings = 0;
        for (List<BlockPos> ring : candidatesByRing) {
            if (!ring.isEmpty()) {
                ring.sort(Comparator.comparingDouble(candidate -> candidate.distSqr(desired)));
                nonEmptyRings++;
            }
        }
        if (nonEmptyRings == 0) {
            return List.of();
        }

        int[] quotas = new int[candidatesByRing.size()];
        int remaining = MAX_PATH_ATTEMPTS;
        for (int ring = 0; ring < candidatesByRing.size(); ring++) {
            if (!candidatesByRing.get(ring).isEmpty()) {
                quotas[ring] = 1;
                remaining--;
            }
        }
        while (remaining > 0) {
            boolean added = false;
            for (int ring = 0; ring < candidatesByRing.size() && remaining > 0; ring++) {
                if (quotas[ring] < candidatesByRing.get(ring).size()) {
                    quotas[ring]++;
                    remaining--;
                    added = true;
                }
            }
            if (!added) {
                break;
            }
        }

        List<BlockPos> selected = new ArrayList<>(MAX_PATH_ATTEMPTS);
        for (int ring = 0; ring < candidatesByRing.size(); ring++) {
            List<BlockPos> candidates = candidatesByRing.get(ring);
            int quota = quotas[ring];
            if (quota == 0) {
                continue;
            }
            for (int sample = 0; sample < quota; sample++) {
                int index = (int) (((long) sample * candidates.size()) / quota);
                selected.add(candidates.get(index));
            }
        }
        return selected;
    }

    /** Rejects positions that cannot hold a villager before invoking pathfinding. */
    private static boolean isValidStandingSpace(Mob mob, BlockPos candidate) {
        var level = mob.level();
        BlockState feet = level.getBlockState(candidate);
        BlockState head = level.getBlockState(candidate.above());
        BlockPos supportPos = candidate.below();
        BlockState support = level.getBlockState(supportPos);

        boolean clear = feet.getCollisionShape(level, candidate).isEmpty()
                && head.getCollisionShape(level, candidate.above()).isEmpty();
        if (!clear) {
            return false;
        }

        boolean supported = support.isFaceSturdy(level, supportPos, Direction.UP)
                || support.is(BlockTags.CLIMBABLE)
                || feet.is(BlockTags.CLIMBABLE);
        if (!supported) {
            return false;
        }

        return feet.getFluidState().isEmpty();
    }

    /**
     * PathNavigation's reach parameter is intentionally one block for these
     * searches. Its canReach flag is therefore insufficient by itself: the
     * actual final node must still be close enough to the task block to use it.
     */
    private static boolean endsWithinInteractionRange(Path path, BlockPos desired) {
        Node end = path.getEndNode();
        if (end == null) {
            return false;
        }
        double dx = end.x - desired.getX();
        double dy = end.y - desired.getY();
        double dz = end.z - desired.getZ();
        return dx * dx + dy * dy + dz * dz <= INTERACTION_RANGE_SQ;
    }
}
