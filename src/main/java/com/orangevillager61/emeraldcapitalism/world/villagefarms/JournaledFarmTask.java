package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.orangevillager61.emeraldcapitalism.world.village.pipeline.VillageBuildingPlacementTask;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * A farm placement that records itself in the village's farm journal: the planned set is saved and
 * the attempt counted before the first block is written, and the farm is marked done only when its
 * placement succeeds. Anything else (failure, stop, crash) leaves the farm pending for a replay.
 *
 * <p>Callers: {@code FarmVillageBuildingProvider} for both its incremental and synchronous
 * placement, and the farm journal GameTests, so the tested path is the production path.
 */
public final class JournaledFarmTask implements VillageBuildingPlacementTask {

    private final VillageFarmPlacer.FarmPlacementTask task;
    private final VillageFarmSavedData farms;
    private final BlockPos villageCenter;
    private final FarmPlacement placement;

    private JournaledFarmTask(VillageFarmPlacer.FarmPlacementTask task, VillageFarmSavedData farms,
                              BlockPos villageCenter, FarmPlacement placement) {
        this.task = task;
        this.farms = farms;
        this.villageCenter = villageCenter;
        this.placement = placement;
    }

    /** Journals the planned set and this attempt, then wraps {@code task}. Call before any block is written. */
    public static JournaledFarmTask begin(VillageFarmSavedData farms, BlockPos villageCenter,
                                          List<FarmPlacement> plannedSet, FarmPlacement placement,
                                          VillageFarmPlacer.FarmPlacementTask task) {
        farms.recordPlannedFarms(villageCenter, plannedSet);
        farms.beginFarmPlacement(villageCenter, placement);
        return new JournaledFarmTask(task, farms, villageCenter, placement);
    }

    @Override
    public boolean processStep() {
        boolean done = task.processStep();
        if (done && task.succeeded()) {
            farms.completeFarmPlacement(villageCenter, placement);
        }
        return done;
    }

    @Override
    public boolean succeeded() {
        return task.succeeded();
    }

    /** The wrapped placement, for its result and phase. */
    public VillageFarmPlacer.FarmPlacementTask placement() {
        return task;
    }
}
