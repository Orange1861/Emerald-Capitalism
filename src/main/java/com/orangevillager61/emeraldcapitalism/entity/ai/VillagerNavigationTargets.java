package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
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
        List<BlockPos> candidates = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1) * 3);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = desired.offset(dx, dy, dz);
                    if (acceptable.test(candidate)) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.distSqr(desired)));

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        int pathAttempts = Math.min(MAX_PATH_ATTEMPTS, candidates.size());
        for (int index = 0; index < pathAttempts; index++) {
            BlockPos candidate = candidates.get(index);
            Path path = navigation.createPath(candidate, 1);
            if (path == null || !path.canReach()) {
                continue;
            }

            // Prefer the task block and then shorter routes to avoid unnecessary
            // detours around an otherwise reachable workstation or crop.
            double score = candidate.distSqr(desired) * 100.0D
                    + path.getNodeCount();
            if (score < bestScore) {
                best = candidate.immutable();
                bestScore = score;
            }
        }
        return best;
    }
}
