package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrain;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingImportance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBankStructurePlacer;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/** Built-in important provider for normal banks and abandoned-village vault ruins. */
public final class BankVillageBuildingProvider implements VillageBuildingProvider {
    public static final ResourceLocation ID = ModIds.id("bank");
    private static final int BANK_FOOTPRINT_AREA = 15 * 15;
    /** Ranked bank sites and orientations tried until one's connector plans. */
    private static final int MAX_CONNECTOR_CANDIDATES = 8;
    /** Server-thread time the candidate connector searches may share before falling back to the best site. */
    private static final long CONNECTOR_SEARCH_BUDGET_NANOS = 40_000_000L;
    /** Rows in front of the door kept free of later buildings; the bank's path start is inside its apron. */
    private static final int APPROACH_ROWS = 16;
    /** A three-wide road plus a graded column on each side. */
    private static final int APPROACH_WIDTH = 5;


    private final VillageBankStructurePlacer placer = new VillageBankStructurePlacer();

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public VillageBuildingImportance importance() {
        return VillageBuildingImportance.IMPORTANT;
    }

    @Override
    public int planningSizeHint() {
        return BANK_FOOTPRINT_AREA;
    }

    @Override
    public List<? extends PlannedVillageBuilding> plan(VillageGenerationContext context) {
        if (context.registryData().hasGeneratedBank(context.villageId())) {
            return List.of();
        }
        if (context.abandonedVillage()) {
            VillageBankStructurePlacer.PlannedAbandonedVault vault = placer.planAbandonedVault(
                    context.level(), context.bellPos(), context.pieces(), context.chunkLoadBudget(),
                    context.reservations()::intersects);
            return vault == null ? List.of() : List.of(new PlannedVault(vault));
        }

        List<VillageBankStructurePlacer.PlannedBank> candidates = placer.planCandidates(
                context.level(), context.bellPos(), context.pieces(),
                context.plannedManagerPos(), context.chunkLoadBudget(),
                context.reservations()::intersects, context.biomeType(), MAX_CONNECTOR_CANDIDATES);
        if (candidates.isEmpty()) {
            return List.of();
        }
        // A site that ranks best on terrain can still sit on a drop or face a
        // wall its road cannot leave. Keep the best candidate whose connector
        // plans; these searches run on the server thread, so they share a budget.
        long deadline = System.nanoTime() + CONNECTOR_SEARCH_BUDGET_NANOS;
        for (int index = 0; index < candidates.size(); index++) {
            VillageBankStructurePlacer.PlannedBank bank = candidates.get(index);
            VillageRoadPathGenerator.PreparedVillageRoads roads = context.preparedRoadsWithReservations()
                    .withAdditionalBuildings(List.of(bank.placementBox()));
            VillageRoadPathGenerator.PlannedPath connector = context.roadGenerator().planBankConnection(
                    VillageTerrain.live(context.level()), bank.pathStart(), bank.pathTarget(), context.pieces(),
                    context.biomeType(), bank.entranceDirection(),
                    roads,
                    context.chunkLoadBudget(), false);
            if (connector != null) {
                if (index > 0) {
                    EmeraldCapitalism.LOGGER.info(
                            "[ECAP] Bank candidate {} at {} facing {} chosen; better-ranked sites had no road out",
                            index + 1, bank.origin(), bank.entranceDirection());
                }
                return List.of(new PlannedBank(bank, connector));
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        return List.of(new PlannedBank(candidates.getFirst(), null));
    }

    private final class PlannedBank implements PlannedVillageBuilding {
        private final VillageBankStructurePlacer.PlannedBank plan;
        private final VillageRoadPathGenerator.PlannedPath connector;
        private VillageBankStructurePlacer.PlacedBank placed;

        private PlannedBank(VillageBankStructurePlacer.PlannedBank plan,
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
        public List<net.minecraft.core.BlockPos> approachColumns() {
            return PlannedVillageBuilding.approachStrip(plan.pathStart(), plan.entranceDirection(),
                    APPROACH_ROWS, APPROACH_WIDTH);
        }

        @Override
        public PathPlanningJob pathPlanningJob(VillageGenerationContext context) {
            /*
             * The bank placer grades and clears the entrance apron during
             * placement. Replan the connector against that final terrain so
             * the first route cells line up with the bank's finished grade.
             * Keep the planning-time connector as a fallback and for the
             * building-planning reservation it already provided.
            */
            VillageRoadPathGenerator.PreparedVillageRoads roads = context.preparedRoadsWithReservations()
                    .withoutBuilding(plan.reservationBox())
                    .withAdditionalBuildings(List.of(plan.placementBox()));
            VillageRoadPathGenerator generator = context.roadGenerator();
            List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = context.pieces();
            String biomeType = context.biomeType();
            List<VillageRoadPathGenerator.PlannedPath> fallback = reservedPaths();
            return terrain -> {
                VillageRoadPathGenerator.PlannedPath postPlacementConnector = generator.planBankConnection(
                        terrain, plan.pathStart(), plan.pathTarget(), pieces, biomeType,
                        plan.entranceDirection(), roads, null, false);
                if (postPlacementConnector != null) {
                    return List.of(postPlacementConnector);
                }
                if (fallback.isEmpty()) {
                    EmeraldCapitalism.LOGGER.warn(
                            "[ECAP] Could not plan a complete bank path from {} to a reachable village street",
                            plan.pathStart());
                }
                return fallback;
            };
        }

        @Override
        public boolean place(VillageGenerationContext context) {
            placed = placer.placePlanned(context.level(), plan);
            if (placed == null) {
                return false;
            }
            context.linkBank(placed.bankPos());
            // Persist immediately after the irreversible template write so a crash
            // cannot cause recovery to generate a duplicate bank.
            context.registryData().markBankGenerated(context.villageId());
            return true;
        }

        @Override
        public VillageBuildingPlacementTask startPlacement(VillageGenerationContext context) {
            // The staged bank is one transaction: the link and marker are written
            // only when its last step commits, and a failure restores the site.
            return VillageBuildingPlacementTask.of(placer.startPlacement(context.level(), plan, result -> {
                placed = result;
                context.linkBank(result.bankPos());
                context.registryData().markBankGenerated(context.villageId());
            }));
        }

        @Override
        public void finish(VillageGenerationContext context) {
            if (placed != null) {
                placer.finishPlannedPlacement(context.level(), placed);
            }
        }
    }

    private final class PlannedVault implements PlannedVillageBuilding {
        private final VillageBankStructurePlacer.PlannedAbandonedVault plan;

        private PlannedVault(VillageBankStructurePlacer.PlannedAbandonedVault plan) {
            this.plan = plan;
        }

        @Override public ResourceLocation providerId() { return ID; }
        @Override public VillageBuildingImportance importance() { return VillageBuildingImportance.IMPORTANT; }
        @Override public BoundingBox reservationBox() { return plan.reservationBox(); }

        @Override
        public boolean place(VillageGenerationContext context) {
            if (placer.placePlannedAbandonedVault(context.level(), plan) == null) {
                return false;
            }
            context.registryData().markBankGenerated(context.villageId());
            return true;
        }
    }
}
