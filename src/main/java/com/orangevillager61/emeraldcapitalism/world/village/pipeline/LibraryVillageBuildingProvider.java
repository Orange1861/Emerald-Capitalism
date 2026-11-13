package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.core.BlockPos;
import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrain;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingImportance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageLibraryStructurePlacer;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/** Important provider that guarantees one vanilla librarian building per village. */
public final class LibraryVillageBuildingProvider implements VillageBuildingProvider {
    public static final ResourceLocation ID = ModIds.id("library");
    private static final int LIBRARY_FOOTPRINT_AREA_HINT = 12 * 12;

    private final VillageLibraryStructurePlacer placer = new VillageLibraryStructurePlacer();

    @Override public ResourceLocation id() { return ID; }
    @Override public VillageBuildingImportance importance() { return VillageBuildingImportance.IMPORTANT; }
    @Override public int planningSizeHint() { return LIBRARY_FOOTPRINT_AREA_HINT; }

    @Override
    public List<? extends PlannedVillageBuilding> plan(VillageGenerationContext context) {
        if (context.registryData().hasGeneratedLibrary(context.villageId())) {
            return List.of();
        }
        VillageLibraryStructurePlacer.PlannedLibrary library = placer.plan(
                context.level(), context.bellPos(), context.biomeType(), context.pieces(),
                context.chunkLoadBudget(), context.reservations()::intersects);
        return library == null ? List.of() : List.of(new PlannedLibrary(library));
    }

    /**
     * Loads the template on the server thread, then searches sites on the
     * planning worker against the village's terrain snapshot.
     */
    @Override
    public VillageBuildingPlanningTask startPlanning(VillageGenerationContext context) {
        if (context.registryData().hasGeneratedLibrary(context.villageId())) {
            return VillageBuildingPlanningTask.completed(List.of());
        }
        VillageLibraryStructurePlacer.SiteSearch search =
                placer.prepareSiteSearch(context.level(), context.biomeType());
        if (search == null) {
            return VillageBuildingPlanningTask.completed(List.of());
        }
        VillageGenerationReservations reservations = context.reservations().copy();
        BlockPos bellPos = context.bellPos();
        List<StructurePiece> pieces = context.pieces();
        return VillagePlanningWorker.planningTask(context, "Library", terrain -> {
            VillageLibraryStructurePlacer.PlannedLibrary library = placer.findSite(
                    terrain, search, bellPos, pieces, reservations::intersects);
            return library == null ? List.of() : List.of(new PlannedLibrary(library));
        });
    }

    private final class PlannedLibrary implements PlannedVillageBuilding {
        private final VillageLibraryStructurePlacer.PlannedLibrary plan;

        private PlannedLibrary(VillageLibraryStructurePlacer.PlannedLibrary plan) {
            this.plan = plan;
        }

        @Override public ResourceLocation providerId() { return ID; }
        @Override public VillageBuildingImportance importance() { return VillageBuildingImportance.IMPORTANT; }
        @Override public BoundingBox reservationBox() { return plan.reservationBox(); }
        @Override public BoundingBox placementBox() { return plan.placementBox(); }

        @Override
        public boolean place(VillageGenerationContext context) {
            if (!placer.place(context.level(), plan, context.biomeType())) {
                return false;
            }
            context.registryData().markLibraryGenerated(context.villageId());
            return true;
        }

        @Override
        public VillageBuildingPlacementTask startPlacement(VillageGenerationContext context) {
            return VillageBuildingPlacementTask.of(placer.startPlacement(context.level(), plan,
                    context.biomeType(),
                    () -> context.registryData().markLibraryGenerated(context.villageId())));
        }

        @Override
        public PathPlanningJob pathPlanningJob(VillageGenerationContext context) {
            // The library reservation includes a deliberate two-block approach
            // apron. Allow this connector to pave that apron while retaining the
            // actual library footprint as a path obstacle. Aim for the street
            // nearest the door: a target near the village center can exhaust the
            // planning time before any closer street is tried.
            VillageRoadPathGenerator.PreparedVillageRoads roads = context.preparedRoadsWithReservations()
                    .withoutBuilding(plan.reservationBox())
                    .withAdditionalBuildings(List.of(plan.placementBox()));
            VillageRoadPathGenerator generator = context.roadGenerator();
            List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = context.pieces();
            String biomeType = context.biomeType();
            return terrain -> {
                VillageRoadPathGenerator.PlannedPath path = generator.planLibraryConnection(
                        terrain, plan.pathStart(), plan.pathStart(), pieces, biomeType,
                        plan.entranceDirection(), roads, null);
                return path == null ? List.of() : List.of(path);
            };
        }
    }
}
