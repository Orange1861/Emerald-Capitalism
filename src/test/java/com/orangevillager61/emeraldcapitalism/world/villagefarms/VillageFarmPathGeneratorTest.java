package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageFarmPathGeneratorTest {

    // Footprint x 100..112, z 200..208 (13 x 9), placed at y 64.
    private static final PlacedFarmInfo FARM = new PlacedFarmInfo(100, 200, 64, 13, 9, 4);

    @Test
    void offersEntrancesEveryThreeBlocksJustOutsideEachEdge() {
        List<VillageFarmPathGenerator.FarmEntrance> entrances =
                VillageFarmPathGenerator.farmEntrances(FARM, new BlockPos(106, 64, 300));

        // Three positions per edge: the midpoint and three blocks either side,
        // never at a corner column.
        assertEquals(12, entrances.size());
        assertEquals(new BlockPos(106, 64, 209), entrances.get(0).start());
        assertEquals(Direction.SOUTH, entrances.get(0).direction());
        for (VillageFarmPathGenerator.FarmEntrance entrance : entrances) {
            BlockPos start = entrance.start();
            switch (entrance.direction()) {
                case EAST -> assertEquals(113, start.getX());
                case WEST -> assertEquals(99, start.getX());
                case SOUTH -> assertEquals(209, start.getZ());
                case NORTH -> assertEquals(199, start.getZ());
                default -> throw new AssertionError("vertical entrance " + entrance);
            }
            assertTrue(start.getX() >= 99 && start.getX() <= 113 && start.getZ() >= 199 && start.getZ() <= 209);
            assertTrue(!(start.getX() == 99 || start.getX() == 113) || (start.getZ() > 200 && start.getZ() < 208),
                    "east and west entrances stay off the corner columns: " + start);
        }
        assertTrue(entrances.stream().anyMatch(entrance -> entrance.start().equals(new BlockPos(103, 64, 199))));
        assertTrue(entrances.stream().anyMatch(entrance -> entrance.start().equals(new BlockPos(113, 64, 207))));
    }

    @Test
    void ordersEntrancesByDistanceToTheVillage() {
        List<Direction> order = VillageFarmPathGenerator.farmEntrances(FARM, new BlockPos(40, 64, 230))
                .stream().map(VillageFarmPathGenerator.FarmEntrance::direction).toList();

        assertEquals(Direction.WEST, order.getFirst());
        assertEquals(Direction.EAST, order.getLast());
    }

    @Test
    void equalDistancesKeepTheFixedEdgeOrder() {
        PlacedFarmInfo square = new PlacedFarmInfo(0, 0, 64, 9, 9, 4);
        List<Direction> order = VillageFarmPathGenerator.farmEntrances(square, new BlockPos(4, 64, 4))
                .stream().map(VillageFarmPathGenerator.FarmEntrance::direction).toList();

        assertEquals(List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH),
                order.subList(0, 4));
    }
}
