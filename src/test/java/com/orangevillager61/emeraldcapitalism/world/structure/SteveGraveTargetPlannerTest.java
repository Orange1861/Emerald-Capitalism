package com.orangevillager61.emeraldcapitalism.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteveGraveTargetPlannerTest {

    @Test
    void requiresBothAxesToClearTheSpawnExclusionZone() {
        BlockPos spawn = BlockPos.ZERO;

        assertTrue(SteveGraveTargetPlanner.satisfiesAxisDistance(
                spawn, new BoundingBox(10_000, 0, -20_000, 10_020, 20, -19_980)));
        assertFalse(SteveGraveTargetPlanner.satisfiesAxisDistance(
                spawn, new BoundingBox(10_000, 0, 9_999, 10_020, 20, 10_020)));
        assertFalse(SteveGraveTargetPlanner.satisfiesAxisDistance(
                spawn, new BoundingBox(-10_020, 0, -10_020, -9_999, 20, -10_000)));
    }
}
