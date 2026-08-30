package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingImportance;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Extension point for village-generation buildings.
 *
 * <p>To add a building without creating new chunk/tick handlers:</p>
 * <ol>
 *   <li>Implement this provider and return task-local {@link PlannedVillageBuilding} objects;
 *       they may retain their placement result for later path/spawn hooks.</li>
 *   <li>Give important buildings a higher {@link VillageBuildingImportance}; otherwise give
 *       {@link #planningSizeHint()} the largest expected footprint so large buildings reserve first.</li>
 *   <li>Reject candidates through {@code context.reservations().intersects(box)}. The pipeline
 *       performs a final overlap check and reserves accepted plans and paths automatically.</li>
 *   <li>Register once with {@link VillageBuildingRegistry#register(VillageBuildingProvider)}.
 *       Do not add another chunk-load listener or server-tick queue.</li>
 * </ol>
 */
public interface VillageBuildingProvider {
    ResourceLocation id();

    default VillageBuildingImportance importance() {
        return VillageBuildingImportance.NORMAL;
    }

    /** Largest normal footprint area; used to decide which same-importance provider plans first. */
    int planningSizeHint();

    List<? extends PlannedVillageBuilding> plan(VillageGenerationContext context);

    /** Optional provider-wide work after all buildings and paths are complete. */
    default VillagePostProcessTask createPostProcessTask(
            VillageGenerationContext context, List<PlannedVillageBuilding> placedBuildings) {
        return null;
    }
}
