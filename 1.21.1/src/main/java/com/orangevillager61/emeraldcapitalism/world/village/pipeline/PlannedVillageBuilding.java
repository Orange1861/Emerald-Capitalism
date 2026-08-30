package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingImportance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/** One fully planned building instance owned by a {@link VillageBuildingProvider}. */
public interface PlannedVillageBuilding {
    ResourceLocation providerId();

    VillageBuildingImportance importance();

    /** Includes any apron that later buildings must not occupy. */
    BoundingBox reservationBox();

    /** Actual structure footprint. It may be smaller than the protected apron above. */
    default BoundingBox placementBox() {
        return reservationBox();
    }

    /** Actual building area used for largest-first ordering within an importance level. */
    default long footprintArea() {
        BoundingBox box = placementBox();
        return (long) (box.maxX() - box.minX() + 1) * (box.maxZ() - box.minZ() + 1);
    }

    /** Paths known during planning, such as the important bank entrance corridor. */
    default List<VillageRoadPathGenerator.PlannedPath> reservedPaths() {
        return List.of();
    }

    /** Places only the structure. Entity spawning and other final work belong in finish(). */
    boolean place(VillageGenerationContext context);

    /** Called after every structure is placed; may plan paths that depend on resolved placement Y. */
    default List<VillageRoadPathGenerator.PlannedPath> pathsAfterPlacement(
            VillageGenerationContext context) {
        return reservedPaths();
    }

    /** Called after all connector paths have been placed. */
    default void finish(VillageGenerationContext context) {
    }
}
