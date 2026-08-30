package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumbermillPlacementScoringTest {

    @Test
    void lumbermillRoadCheckUsesCachedStreetColumns() {
        Set<Long> streets = Set.of(streetKey(4, 6));

        assertTrue(VillageRoadPathGenerator.PreparedVillageRoads.intersectsStreet(
                new BoundingBox(2, 60, 4, 6, 70, 8), streets));
        assertFalse(VillageRoadPathGenerator.PreparedVillageRoads.intersectsStreet(
                new BoundingBox(2, 60, 2, 3, 70, 4), streets));
    }

    @Test
    void entranceStairFacesBackTowardTheDoor() {
        assertEquals(Direction.WEST,
                VillageLumbermillStructurePlacer.entranceStairFacing(Direction.EAST));
        assertEquals(Direction.EAST,
                VillageLumbermillStructurePlacer.entranceStairFacing(Direction.WEST));
        assertEquals(Direction.NORTH,
                VillageLumbermillStructurePlacer.entranceStairFacing(Direction.SOUTH));
        assertEquals(Direction.SOUTH,
                VillageLumbermillStructurePlacer.entranceStairFacing(Direction.NORTH));
    }

    private static long streetKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
