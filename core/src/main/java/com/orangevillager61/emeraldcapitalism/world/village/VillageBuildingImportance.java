package com.orangevillager61.emeraldcapitalism.world.village;

/** Stable priority levels used when ordering village building work. */
public enum VillageBuildingImportance {
    OPTIONAL(0),
    NORMAL(100),
    IMPORTANT(200),
    CRITICAL(300);

    private final int weight;

    VillageBuildingImportance(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
