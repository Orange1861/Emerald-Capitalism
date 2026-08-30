package com.orangevillager61.emeraldcapitalism.worldgen;

import net.minecraft.core.BlockPos;

/** The 1.21.1 positional seed algorithm used by structure processors. */
public final class PositionalSeed {

    private PositionalSeed() {
    }

    public static long of(BlockPos pos) {
        return of(pos.getX(), pos.getY(), pos.getZ());
    }

    public static long of(int x, int y, int z) {
        long seed = (long) x * 3_129_871L
                ^ (long) z * 116_129_781L
                ^ (long) y;
        seed = seed * seed * 42_317_861L + seed * 11L;
        return seed >> 16;
    }
}
