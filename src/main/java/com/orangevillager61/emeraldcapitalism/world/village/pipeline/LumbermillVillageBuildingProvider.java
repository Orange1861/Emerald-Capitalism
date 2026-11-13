package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrain;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingImportance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageLumbermillStructurePlacer;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/** Important provider that guarantees one lumbermill for each normal village. */
public final class LumbermillVillageBuildingProvider implements VillageBuildingProvider {
    public static final ResourceLocation ID = ModIds.id("lumbermill");
    private static final int LARGEST_EXPECTED_LUMBERMILL_AREA = 26 * 14;

    private final VillageLumbermillStructurePlacer placer = new VillageLumbermillStructurePlacer();

    @Override public ResourceLocation id() { return ID; }
    @Override public VillageBuildingImportance importance() { return VillageBuildingImportance.IMPORTANT; }
    @Override public int planningSizeHint() { return LARGEST_EXPECTED_LUMBERMILL_AREA; }

    @Override
    public List<? extends PlannedVillageBuilding> plan(VillageGenerationContext context) {
        VillageBuildingPlanningTask task = startPlanning(context);
        while (!task.processStep()) {
            // Run every step now; the pipeline calls startPlanning to spread them over ticks.
        }
        return task.result();
    }

    /**
     * The site search and the lumbermill's connector run as one job on the
     * planning worker, against the village's terrain snapshot. The server thread
     * only loads the templates, copies the current reservations and roads, and
     * checks each tick whether the job has finished.
     */
    @Override
    public VillageBuildingPlanningTask startPlanning(VillageGenerationContext context) {
        if (context.abandonedVillage()
                || context.registryData().hasGeneratedLumbermill(context.villageId())) {
            return VillageBuildingPlanningTask.completed(List.of());
        }
        // Only a snapshot may be read off the server thread. Without one, as when
        // planning outside the pipeline, the job runs here against the live level.
        boolean onWorker = context.terrainSnapshot() != null;
        VillageTerrain terrain = onWorker ? context.terrainSnapshot() : VillageTerrain.live(context.level());
        VillageGenerationReservations reservations = context.reservations().copy();
        VillageLumbermillStructurePlacer.SitePlanning sitePlanning = placer.startPlanning(
                context.level(), terrain, context.bellPos(), context.biomeType(), context.pieces(),
                context.preparedRoads(), reservations::intersects);
        if (sitePlanning == null) {
            return VillageBuildingPlanningTask.completed(List.of());
        }
        VillageRoadPathGenerator.PreparedVillageRoads roads = context.preparedRoadsWithReservations();
        VillageRoadPathGenerator generator = context.roadGenerator();
        List<StructurePiece> pieces = context.pieces();
        String biomeType = context.biomeType();
        java.util.function.Supplier<List<PlannedVillageBuilding>> search = () -> {
            while (!sitePlanning.step()) {
                // The whole search runs in one job; batches only matter on the server thread.
            }
            VillageLumbermillStructurePlacer.PlannedLumbermill lumbermill = sitePlanning.result();
            return lumbermill == null ? List.of()
                    : List.of(planConnector(terrain, generator, roads, pieces, biomeType, lumbermill));
        };
        CompletableFuture<List<PlannedVillageBuilding>> job = onWorker
                ? VillagePlanningWorker.submit(search)
                : CompletableFuture.completedFuture(search.get());
        return new VillageBuildingPlanningTask() {
            private List<? extends PlannedVillageBuilding> result;

            @Override
            public boolean processStep() {
                if (result != null) {
                    return true;
                }
                if (!job.isDone()) {
                    return false;
                }
                try {
                    result = job.join();
                } catch (RuntimeException exception) {
                    EmeraldCapitalism.LOGGER.error("[ECAP] Lumbermill planning failed near {}",
                            context.bellPos(), exception);
                    result = List.of();
                }
                return true;
            }

            @Override
            public List<? extends PlannedVillageBuilding> result() {
                return result == null ? List.of() : result;
            }
        };
    }

    private PlannedVillageBuilding planConnector(VillageTerrain terrain, VillageRoadPathGenerator generator,
                                                 VillageRoadPathGenerator.PreparedVillageRoads villageRoads,
                                                 List<StructurePiece> pieces, String biomeType,
                                                 VillageLumbermillStructurePlacer.PlannedLumbermill lumbermill) {
        // The building reservation is committed after this provider returns,
        // so include this footprint while planning its own connector. A first
        // turn must not widen the path back over the entrance stairs. The
        // lumbermill can stand far outside the village core, so aim for the
        // street nearest its door rather than the village center.
        VillageRoadPathGenerator.PreparedVillageRoads roads =
                villageRoads.withAdditionalBuildings(List.of(lumbermill.placementBox()));
        VillageRoadPathGenerator.PlannedPath connector = generator.planLumbermillConnection(
                terrain, lumbermill.pathStart(), lumbermill.pathStart(), pieces, biomeType,
                lumbermill.entranceDirection(), roads, null);
        return new PlannedLumbermill(lumbermill, connector);
    }

    private final class PlannedLumbermill implements PlannedVillageBuilding {
        private final VillageLumbermillStructurePlacer.PlannedLumbermill plan;
        private final VillageRoadPathGenerator.PlannedPath connector;

        private PlannedLumbermill(VillageLumbermillStructurePlacer.PlannedLumbermill plan,
                                  VillageRoadPathGenerator.PlannedPath connector) {
            this.plan = plan;
            this.connector = connector;
        }

        @Override public ResourceLocation providerId() { return ID; }
        @Override public VillageBuildingImportance importance() { return VillageBuildingImportance.IMPORTANT; }
        @Override public BoundingBox reservationBox() { return plan.reservationBox(); }
        @Override public BoundingBox placementBox() { return plan.placementBox(); }

        @Override
        public List<VillageRoadPathGenerator.PlannedPath> reservedPaths() {
            return connector == null ? List.of() : List.of(connector);
        }

        @Override
        public boolean place(VillageGenerationContext context) {
            if (!placer.place(context.level(), plan, context.biomeType())) {
                return false;
            }
            // Commit the one-per-village marker immediately after the irreversible
            // template write so a restart cannot place a duplicate lumbermill.
            context.registryData().markLumbermillGenerated(context.villageId());
            return true;
        }

        @Override
        public VillageBuildingPlacementTask startPlacement(VillageGenerationContext context) {
            // The marker is committed with the first template slice, the first
            // irreversible template write, so a restart cannot place a duplicate.
            return VillageBuildingPlacementTask.of(placer.startPlacement(context.level(), plan,
                    context.biomeType(),
                    () -> context.registryData().markLumbermillGenerated(context.villageId())));
        }

        @Override
        public void finish(VillageGenerationContext context) {
            placer.spawnVillagers(context.level(), plan.placementBox());
        }
    }
}
