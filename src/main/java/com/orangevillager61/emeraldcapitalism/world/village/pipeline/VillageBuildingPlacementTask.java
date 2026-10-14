package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

/** A building placement job that can yield between server ticks. */
public interface VillageBuildingPlacementTask {
    /** @return true when all placement work has completed */
    boolean processStep();

    /** @return whether the completed placement produced a usable building */
    boolean succeeded();
}
