package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.world.village.StagedPlacement;

/** A building placement job that can yield between server ticks. */
public interface VillageBuildingPlacementTask {
    /** @return true when all placement work has completed */
    boolean processStep();

    /** @return whether the completed placement produced a usable building */
    boolean succeeded();

    /**
     * Runs every remaining step now. The pipeline calls this at server shutdown,
     * while the level is still loaded, so no building is left part-placed.
     */
    default void finishNow() {
        while (!processStep()) {
            // Each call advances one step.
        }
    }

    static VillageBuildingPlacementTask of(StagedPlacement placement) {
        return new VillageBuildingPlacementTask() {
            @Override
            public boolean processStep() {
                return placement.processStep();
            }

            @Override
            public boolean succeeded() {
                return placement.succeeded();
            }
        };
    }
}
