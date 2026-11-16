package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannedVillageBuildingApproachTest {

    @Test
    void stripRunsOutFromTheDoorAndSpansItsWidth() {
        List<BlockPos> strip = PlannedVillageBuilding.approachStrip(new BlockPos(0, 64, 0), Direction.NORTH, 3, 5);

        assertEquals(15, strip.size());
        assertTrue(strip.contains(new BlockPos(0, 64, 0)));
        assertTrue(strip.contains(new BlockPos(-2, 64, -2)));
        assertTrue(strip.contains(new BlockPos(2, 64, -2)));
        assertFalse(strip.contains(new BlockPos(0, 64, 1)), "the strip must not reach behind the door");
        assertFalse(strip.contains(new BlockPos(0, 64, -3)));
        assertFalse(strip.contains(new BlockPos(3, 64, 0)));
    }

    @Test
    void stripFollowsAnEastFacingDoor() {
        List<BlockPos> strip = PlannedVillageBuilding.approachStrip(new BlockPos(10, 70, 10), Direction.EAST, 2, 3);

        assertEquals(6, strip.size());
        assertTrue(strip.contains(new BlockPos(11, 70, 9)));
        assertTrue(strip.contains(new BlockPos(11, 70, 11)));
        assertFalse(strip.contains(new BlockPos(9, 70, 10)));
    }
}
