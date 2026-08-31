package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

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

        VillageBankStructurePlacer.PlannedBank bank = placer.plan(
                context.level(), context.bellPos(), context.pieces(),
                context.plannedManagerPos(), context.chunkLoadBudget(),
                context.reservations()::intersects);
        if (bank == null) {
            return List.of();
        }
        VillageRoadPathGenerator.PreparedVillageRoads roads = context.preparedRoadsWithReservations()
                .withAdditionalBuildings(List.of(bank.placementBox()));
        VillageRoadPathGenerator.PlannedPath connector = context.roadGenerator().planBankConnection(
                context.level(), bank.pathStart(), bank.pathTarget(), context.pieces(),
                context.biomeType(), bank.entranceDirection(),
                roads,
                context.chunkLoadBudget());
        return List.of(new PlannedBank(bank, connector));
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
        public List<VillageRoadPathGenerator.PlannedPath> pathsAfterPlacement(
                VillageGenerationContext context) {
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
            VillageRoadPathGenerator.PlannedPath postPlacementConnector =
                    context.roadGenerator().planBankConnection(
                            context.level(), plan.pathStart(), plan.pathTarget(), context.pieces(),
                            context.biomeType(), plan.entranceDirection(),
                            roads, context.chunkLoadBudget());
            if (postPlacementConnector != null) {
                return List.of(postPlacementConnector);
            }
            return reservedPaths();
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
