package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/** Detects a custom movement goal that has stopped making progress toward its target. */
public final class VillagerNavigationWatchdog {

    private static final int MAX_STAGNANT_TICKS = 100;
    private static final double MIN_WINDOW_PROGRESS_SQ = 0.0625D;
    private static final double POSITION_REVISIT_DISTANCE_SQ = 0.09D;
    private static final int POSITION_HISTORY_LIMIT = 16;

    @Nullable
    private BlockPos target;
    @Nullable
    private Vec3 progressWindowOrigin;
    private final Deque<Vec3> positionHistory = new ArrayDeque<>();
    private int furthestRouteNode = -1;
    private int stagnantTicks;

    public void reset() {
        target = null;
        progressWindowOrigin = null;
        positionHistory.clear();
        furthestRouteNode = -1;
        stagnantTicks = 0;
    }

    /**
     * Returns true after roughly five seconds without meaningful movement. Callers
     * should only invoke this while the villager is still outside its arrival range.
     */
    public boolean isStuck(Villager villager, BlockPos target) {
        if (!target.equals(this.target)) {
            this.target = target.immutable();
            progressWindowOrigin = villager.position();
            positionHistory.clear();
            positionHistory.addLast(progressWindowOrigin);
            furthestRouteNode = -1;
            stagnantTicks = 0;
            return false;
        }

        Vec3 currentPosition = villager.position();
        stagnantTicks++;

        Path path = villager.getNavigation().getPath();
        if (path != null) {
            int nextNode = path.getNextNodeIndex();
            if (furthestRouteNode < 0) {
                furthestRouteNode = nextNode;
            } else if (nextNode > furthestRouteNode) {
                furthestRouteNode = nextNode;
                recordProgress(currentPosition);
                return false;
            }
        }

        boolean revisiting = positionHistory.stream()
                .anyMatch(previous -> currentPosition.distanceToSqr(previous)
                        <= POSITION_REVISIT_DISTANCE_SQ);
        positionHistory.addLast(currentPosition);
        while (positionHistory.size() > POSITION_HISTORY_LIMIT) {
            positionHistory.removeFirst();
        }

        // Position history is a fallback for navigation states that do not
        // expose route advancement, such as a ladder path that is temporarily
        // cleared after its same-column node is marked reached. It deliberately
        // requires a meaningful window of novel movement, so tiny displacements
        // cannot keep an obstructed villager alive forever.
        if (progressWindowOrigin == null) {
            recordProgress(currentPosition);
        } else if (!revisiting
                && currentPosition.distanceToSqr(progressWindowOrigin) >= MIN_WINDOW_PROGRESS_SQ) {
            recordProgress(currentPosition);
        }

        return stagnantTicks >= MAX_STAGNANT_TICKS;
    }

    private void recordProgress(Vec3 currentPosition) {
        progressWindowOrigin = currentPosition;
        stagnantTicks = 0;
        if (positionHistory.isEmpty()) {
            positionHistory.addLast(currentPosition);
        }
    }
}
