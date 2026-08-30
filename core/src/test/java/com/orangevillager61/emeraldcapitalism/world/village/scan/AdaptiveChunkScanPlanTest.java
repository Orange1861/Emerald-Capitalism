package com.orangevillager61.emeraldcapitalism.world.village.scan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveChunkScanPlanTest {

    @Test
    void visitsChunksInBellOutwardRings() {
        AdaptiveChunkScanPlan plan = new AdaptiveChunkScanPlan(0, 0, -4, 4, -4, 4, List.of());
        List<AdaptiveChunkScanPlan.ChunkCoordinate> visited = new ArrayList<>();
        int previousRadius = -1;

        while (!plan.isComplete()) {
            assertTrue(plan.currentRadius() >= previousRadius);
            previousRadius = plan.currentRadius();
            visited.add(plan.currentChunk());
            plan.completeCurrentChunk(AdaptiveChunkScanPlan.ChunkOutcome.INTERESTING);
        }

        assertEquals(new AdaptiveChunkScanPlan.ChunkCoordinate(0, 0), visited.getFirst());
        assertEquals(81, visited.size());
        assertEquals(81, new HashSet<>(visited).size());
    }

    @Test
    void stopsOnlyAfterThreeCompleteSequentialEmptySegments() {
        AdaptiveChunkScanPlan plan = new AdaptiveChunkScanPlan(0, 0, -7, 7, -7, 7, List.of());
        Set<Integer> northRadii = new HashSet<>();

        while (!plan.isComplete()) {
            int radius = plan.currentRadius();
            AdaptiveChunkScanPlan.Sector sector = plan.currentSector();
            if (sector == AdaptiveChunkScanPlan.Sector.NORTH) {
                northRadii.add(radius);
            }
            AdaptiveChunkScanPlan.ChunkOutcome outcome = sector == AdaptiveChunkScanPlan.Sector.NORTH && radius != 2
                    ? AdaptiveChunkScanPlan.ChunkOutcome.EMPTY
                    : AdaptiveChunkScanPlan.ChunkOutcome.INTERESTING;
            plan.completeCurrentChunk(outcome);
        }

        assertEquals(Set.of(1, 2, 3, 4, 5), northRadii);
        assertFalse(northRadii.contains(6));
    }

    @Test
    void unavailableSegmentBreaksTheSequentialEmptyStreak() {
        AdaptiveChunkScanPlan plan = new AdaptiveChunkScanPlan(0, 0, -7, 7, -7, 7, List.of());
        Set<Integer> northRadii = new HashSet<>();

        while (!plan.isComplete()) {
            int radius = plan.currentRadius();
            AdaptiveChunkScanPlan.Sector sector = plan.currentSector();
            if (sector == AdaptiveChunkScanPlan.Sector.NORTH) {
                northRadii.add(radius);
            }
            AdaptiveChunkScanPlan.ChunkOutcome outcome;
            if (sector != AdaptiveChunkScanPlan.Sector.NORTH) {
                outcome = AdaptiveChunkScanPlan.ChunkOutcome.INTERESTING;
            } else if (radius == 2) {
                outcome = AdaptiveChunkScanPlan.ChunkOutcome.UNKNOWN;
            } else {
                outcome = AdaptiveChunkScanPlan.ChunkOutcome.EMPTY;
            }
            plan.completeCurrentChunk(outcome);
        }

        assertEquals(Set.of(1, 2, 3, 4, 5), northRadii);
    }

    @Test
    void scansThreeEmptySegmentsBeyondRequiredAnchorExtent() {
        AdaptiveChunkScanPlan plan = new AdaptiveChunkScanPlan(
                0,
                0,
                -9,
                9,
                -9,
                9,
                List.of(new AdaptiveChunkScanPlan.ChunkCoordinate(0, -5))
        );
        Set<Integer> northRadii = new HashSet<>();

        while (!plan.isComplete()) {
            if (plan.currentSector() == AdaptiveChunkScanPlan.Sector.NORTH) {
                northRadii.add(plan.currentRadius());
                plan.completeCurrentChunk(AdaptiveChunkScanPlan.ChunkOutcome.EMPTY);
            } else {
                plan.completeCurrentChunk(AdaptiveChunkScanPlan.ChunkOutcome.INTERESTING);
            }
        }

        assertTrue(northRadii.containsAll(Set.of(1, 2, 3, 4, 5, 6, 7, 8)));
        assertFalse(northRadii.contains(9));
    }

    @Test
    void fullyEmptyScanStopsAfterThirdRing() {
        AdaptiveChunkScanPlan plan = new AdaptiveChunkScanPlan(0, 0, -8, 8, -8, 8, List.of());
        int visited = 0;

        while (!plan.isComplete()) {
            visited++;
            plan.completeCurrentChunk(AdaptiveChunkScanPlan.ChunkOutcome.EMPTY);
        }

        assertEquals(49, visited);
    }

    @Test
    void lookAheadStartsAtCurrentChunkAndNeverCrossesARepeatedSector() {
        AdaptiveChunkScanPlan plan = new AdaptiveChunkScanPlan(0, 0, -8, 8, -8, 8, List.of());

        List<AdaptiveChunkScanPlan.ChunkCoordinate> lookAhead = plan.upcomingChunks(4);

        assertEquals(4, lookAhead.size());
        assertEquals(plan.currentChunk(), lookAhead.getFirst());
        assertEquals(4, new HashSet<>(lookAhead).size());
    }
}
