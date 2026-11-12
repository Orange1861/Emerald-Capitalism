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

    /**
     * Whether an entrance stepping {@code (stepX, stepZ)} points toward a target
     * offset {@code (toTargetX, toTargetZ)}: within 60 degrees of it, so a
     * diagonal site accepts both entrances that face the village at 45 degrees.
     * A target at the entrance itself counts as faced.
     */
    public static boolean entranceFacesTarget(int stepX, int stepZ, int toTargetX, int toTargetZ) {
        double dot = (double) stepX * toTargetX + (double) stepZ * toTargetZ;
        double stepLengthSq = (double) stepX * stepX + (double) stepZ * stepZ;
        double targetLengthSq = (double) toTargetX * toTargetX + (double) toTargetZ * toTargetZ;
        if (targetLengthSq == 0.0D) {
            return true;
        }
        return dot > 0.0D && 4.0D * dot * dot >= stepLengthSq * targetLengthSq;
    }
}
