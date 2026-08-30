package com.orangevillager61.emeraldcapitalism.world.village;

/** Platform-free scoring rules used when selecting a lumbermill site. */
public final class LumbermillPlacementScoring {
    public static final int TREE_ROUGHNESS_TOLERANCE = 6;

    private LumbermillPlacementScoring() {
    }

    public static boolean withinTreePreferenceTolerance(int roughness, int minimumRoughness) {
        return roughness <= minimumRoughness + TREE_ROUGHNESS_TOLERANCE;
    }

    public static int normalizeTreeSignal(int weightedSignal, int sampleCount) {
        if (weightedSignal <= 0 || sampleCount <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE,
                (long) weightedSignal * 1_000L / sampleCount);
    }
}
