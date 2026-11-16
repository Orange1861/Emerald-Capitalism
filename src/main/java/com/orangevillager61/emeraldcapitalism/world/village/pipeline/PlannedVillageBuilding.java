package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingImportance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
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

    /**
     * Columns straight out from the door that later buildings, such as outskirt
     * farms, must leave free. The connector is replanned after placement and may
     * not have been planned yet, so this keeps the door's way out open even when
     * {@link #reservedPaths()} is empty.
     */
    default List<BlockPos> approachColumns() {
        return List.of();
    }

    /**
     * A strip {@code width} columns wide running {@code rows} columns from
     * {@code start} in {@code direction}.
     */
    static List<BlockPos> approachStrip(BlockPos start, Direction direction, int rows, int width) {
        Direction side = direction.getClockWise();
        int firstOffset = -(width / 2);
        List<BlockPos> columns = new ArrayList<>(rows * width);
        for (int row = 0; row < rows; row++) {
            BlockPos center = start.relative(direction, row);
            for (int offset = firstOffset; offset < firstOffset + width; offset++) {
                columns.add(center.relative(side, offset));
            }
        }
        return List.copyOf(columns);
    }

    /** Places only the structure. Entity spawning and other final work belong in finish(). */
    boolean place(VillageGenerationContext context);

    /**
     * Starts an incremental placement job when this building has expensive terrain or
     * post-template work. Returning {@code null} keeps the legacy synchronous path.
     */
    default VillageBuildingPlacementTask startPlacement(VillageGenerationContext context) {
        return null;
    }

    /**
     * Called on the server thread after every structure is placed. Returns the
     * job that plans this building's connectors on the planning worker, against
     * a terrain copy taken after placement; it may depend on resolved placement Y.
     */
    default PathPlanningJob pathPlanningJob(VillageGenerationContext context) {
        List<VillageRoadPathGenerator.PlannedPath> reserved = reservedPaths();
        return terrain -> reserved;
    }

    /** Called after all connector paths have been placed. */
    default void finish(VillageGenerationContext context) {
    }
}
