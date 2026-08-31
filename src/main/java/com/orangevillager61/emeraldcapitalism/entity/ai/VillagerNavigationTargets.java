package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** Small server-side helpers for choosing a reachable standing target near a task block. */
public final class VillagerNavigationTargets {

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
        PathNavigation navigation = mob.getNavigation();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = desired.offset(dx, dy, dz);
                    if (!acceptable.test(candidate)) {
                        continue;
                    }

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
            }
        }
        return best;
    }
}
