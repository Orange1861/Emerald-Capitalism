package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrain;

import java.util.List;

/**
 * Connector planning for one placed building. The building prepares the job on
 * the server thread with every input it needs already computed; the job then
 * runs on the planning worker and reads only {@code terrain}, never a level or
 * the generation context.
 */
@FunctionalInterface
public interface PathPlanningJob {
    List<VillageRoadPathGenerator.PlannedPath> plan(VillageTerrain terrain);
}
