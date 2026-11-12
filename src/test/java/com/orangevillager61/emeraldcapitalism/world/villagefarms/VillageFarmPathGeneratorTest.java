package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VillageFarmPathGeneratorTest {

    // Footprint x 100..112, z 200..208 (13 x 9), placed at y 64.
    private static final PlacedFarmInfo FARM = new PlacedFarmInfo(100, 200, 64, 13, 9, 4);

    @Test
    void offersEveryEdgeJustOutsideTheFootprint() {
        List<VillageFarmPathGenerator.FarmEntrance> entrances =
                VillageFarmPathGenerator.farmEntrances(FARM, new BlockPos(106, 64, 300));

        assertEquals(4, entrances.size());
        assertEquals(new BlockPos(106, 64, 209), entrances.get(0).start());
        assertEquals(Direction.SOUTH, entrances.get(0).direction());
        assertEquals(new BlockPos(106, 64, 199), entrances.get(3).start());
        assertEquals(Direction.NORTH, entrances.get(3).direction());
    }

    @Test
    void ordersEdgesByDistanceToTheVillage() {
        List<Direction> order = VillageFarmPathGenerator.farmEntrances(FARM, new BlockPos(40, 64, 230))
                .stream().map(VillageFarmPathGenerator.FarmEntrance::direction).toList();

        assertEquals(List.of(Direction.WEST, Direction.SOUTH, Direction.NORTH, Direction.EAST), order);
    }

    @Test
    void equalDistancesKeepTheFixedEdgeOrder() {
        PlacedFarmInfo square = new PlacedFarmInfo(0, 0, 64, 9, 9, 4);
        List<Direction> order = VillageFarmPathGenerator.farmEntrances(square, new BlockPos(4, 64, 4))
                .stream().map(VillageFarmPathGenerator.FarmEntrance::direction).toList();

        assertEquals(List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH), order);
    }
}
