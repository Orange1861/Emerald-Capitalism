package com.orangevillager61.emeraldcapitalism.world.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumbermillPlacementScoringTest {

    @Test
    void normalizesTreeSignalAcrossDifferentLoadedSampleCounts() {
        assertEquals(2_000, LumbermillPlacementScoring.normalizeTreeSignal(4, 2));
        assertEquals(2_000, LumbermillPlacementScoring.normalizeTreeSignal(8, 4));
        assertEquals(0, LumbermillPlacementScoring.normalizeTreeSignal(0, 8));
        assertEquals(0, LumbermillPlacementScoring.normalizeTreeSignal(8, 0));
    }

    @Test
    void treePreferenceAllowsOnlySmallTerrainTradeoffs() {
        assertTrue(LumbermillPlacementScoring.withinTreePreferenceTolerance(6, 0));
        assertFalse(LumbermillPlacementScoring.withinTreePreferenceTolerance(7, 0));
        assertTrue(LumbermillPlacementScoring.withinTreePreferenceTolerance(10, 4));
    }

    @Test
    void entranceFacesTargetWithinSixtyDegrees() {
        assertTrue(LumbermillPlacementScoring.entranceFacesTarget(1, 0, 20, 0));
        assertTrue(LumbermillPlacementScoring.entranceFacesTarget(1, 0, 20, 20));
        assertTrue(LumbermillPlacementScoring.entranceFacesTarget(0, -1, 10, -10));
        assertTrue(LumbermillPlacementScoring.entranceFacesTarget(1, 0, 10, 17));
        assertFalse(LumbermillPlacementScoring.entranceFacesTarget(1, 0, 10, 18));
        assertFalse(LumbermillPlacementScoring.entranceFacesTarget(1, 0, 0, 20));
        assertFalse(LumbermillPlacementScoring.entranceFacesTarget(1, 0, -20, 0));
        assertTrue(LumbermillPlacementScoring.entranceFacesTarget(0, 1, 0, 0));
        assertTrue(LumbermillPlacementScoring.entranceFacesTarget(
                1, 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
