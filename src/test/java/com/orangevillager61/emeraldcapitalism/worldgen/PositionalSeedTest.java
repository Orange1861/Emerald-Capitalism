package com.orangevillager61.emeraldcapitalism.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionalSeedTest {

    @Test
    void matchesTheOnePointTwentyOnePointOneAlgorithmAcrossSignedCoordinates() {
        int[][] coordinates = {
                {-31, -7, -19},
                {-1, 0, 1},
                {0, 0, 0},
                {1, 0, -1},
                {27, 9, 34}
        };

        for (int[] coordinate : coordinates) {
            assertEquals(vanillaSeed(coordinate[0], coordinate[1], coordinate[2]),
                    PositionalSeed.of(coordinate[0], coordinate[1], coordinate[2]));
        }
    }

    private static long vanillaSeed(int x, int y, int z) {
        long seed = (long) x * 3_129_871L
                ^ (long) z * 116_129_781L
                ^ (long) y;
        seed = seed * seed * 42_317_861L + seed * 11L;
        return seed >> 16;
    }
}
