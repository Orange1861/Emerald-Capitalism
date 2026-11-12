package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

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
     * Site search runs in time-bounded batches, then the connector is planned in
     * its own step. Neither split changes the chosen site or connector.
     */
    @Override
    public VillageBuildingPlanningTask startPlanning(VillageGenerationContext context) {
        if (context.abandonedVillage()
                || context.registryData().hasGeneratedLumbermill(context.villageId())) {
            return VillageBuildingPlanningTask.completed(List.of());
        }
        VillageLumbermillStructurePlacer.SitePlanning sitePlanning = placer.startPlanning(
                context.level(), context.bellPos(), context.biomeType(), context.pieces(),
                context.preparedRoads(),
                context.chunkLoadBudget(), context.reservations()::intersects);
        if (sitePlanning == null) {
            return VillageBuildingPlanningTask.completed(List.of());
        }
        return new VillageBuildingPlanningTask() {
            private List<? extends PlannedVillageBuilding> result;

            @Override
            public boolean processStep() {
                if (result != null) {
                    return true;
                }
                if (!sitePlanning.isFinished()) {
                    sitePlanning.step();
                    return false;
                }
                VillageLumbermillStructurePlacer.PlannedLumbermill lumbermill = sitePlanning.result();
                result = lumbermill == null ? List.of() : planConnector(context, lumbermill);
                return true;
            }

            @Override
            public List<? extends PlannedVillageBuilding> result() {
                return result == null ? List.of() : result;
            }
        };
    }

    private List<? extends PlannedVillageBuilding> planConnector(
            VillageGenerationContext context, VillageLumbermillStructurePlacer.PlannedLumbermill lumbermill) {
        // The building reservation is committed after this provider returns,
        // so include this footprint while planning its own connector. A first
        // turn must not widen the path back over the entrance stairs. The
        // lumbermill can stand far outside the village core, so aim for the
        // street nearest its door rather than the village center.
        VillageRoadPathGenerator.PreparedVillageRoads roads =
                context.preparedRoadsWithReservations()
                        .withAdditionalBuildings(List.of(lumbermill.placementBox()));
        VillageRoadPathGenerator.PlannedPath connector = context.roadGenerator().planLumbermillConnection(
                context.level(), lumbermill.pathStart(), lumbermill.pathStart(), context.pieces(),
                context.biomeType(), lumbermill.entranceDirection(),
                roads, context.chunkLoadBudget());
        return List.of(new PlannedLumbermill(lumbermill, connector));
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
        public void finish(VillageGenerationContext context) {
            placer.spawnVillagers(context.level(), plan.placementBox());
        }
    }
}
