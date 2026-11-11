package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import java.util.List;

/** A provider planning job that can yield between server ticks. */
public interface VillageBuildingPlanningTask {
    /** @return true when planning has completed and {@link #result()} is final */
    boolean processStep();

    /** Candidate plans for the pipeline to reserve; only read after completion. */
    List<? extends PlannedVillageBuilding> result();

    /** Wraps work that always finishes in one step. */
    static VillageBuildingPlanningTask completed(List<? extends PlannedVillageBuilding> plans) {
        return new VillageBuildingPlanningTask() {
            @Override
            public boolean processStep() {
                return true;
            }

            @Override
            public List<? extends PlannedVillageBuilding> result() {
                return plans;
            }
        };
    }
}
