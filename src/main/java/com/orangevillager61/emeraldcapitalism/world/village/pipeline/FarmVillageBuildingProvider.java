package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingImportance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.FarmPlacement;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.PlacedFarmInfo;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmPathGenerator;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmPlacer;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSiteSelector;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/** Built-in normal-importance provider for all outskirt farm instances. */
public final class FarmVillageBuildingProvider implements VillageBuildingProvider {
    public static final ResourceLocation ID = ModIds.id("outskirt_farm");
    private static final int LARGEST_EXPECTED_FARM_AREA = 20 * 20;

    private final VillageFarmPlacer placer = new VillageFarmPlacer();
    private final VillageFarmPathGenerator pathGenerator = new VillageFarmPathGenerator();

    @Override public ResourceLocation id() { return ID; }
    @Override public int planningSizeHint() { return LARGEST_EXPECTED_FARM_AREA; }

    @Override
    public List<? extends PlannedVillageBuilding> plan(VillageGenerationContext context) {
        if (!Config.outskirtFarmsEnabled.get()
                || context.farmSavedData().areFarmsPlaced(context.structureCenter())) {
            return List.of();
        }

        VillageFarmSiteSelector.VillageSpatialCache cache = context.spatialCacheWithReservations();
        List<FarmPlacement> sites = context.farmSiteSelector().findSites(
                context.level(), context.structureCenter(), context.biomeType(),
                context.villageBox(), context.pieces(), context.chunkLoadBudget(), cache);
        List<PlannedFarm> result = new ArrayList<>(sites.size());
        for (FarmPlacement site : sites) {
            result.add(new PlannedFarm(site, cache));
        }
        return result;
    }

    @Override
    public VillagePostProcessTask createPostProcessTask(
            VillageGenerationContext context, List<PlannedVillageBuilding> placedBuildings) {
        if (!Config.outskirtFarmsWaterContainmentEnabled.get()
                || context.farmSavedData().areFarmsPlaced(context.structureCenter())) {
            return null;
        }
        List<PlacedFarmInfo> farms = placedBuildings.stream()
                .filter(PlannedFarm.class::isInstance)
                .map(PlannedFarm.class::cast)
                .map(plan -> plan.placed)
                .filter(java.util.Objects::nonNull)
                .toList();
        VillageFarmPlacer.WaterContainmentTask water =
                placer.createWaterContainmentTask(context.villageBox(), farms);
        return () -> water.process(context.level());
    }

    private final class PlannedFarm implements PlannedVillageBuilding {
        private final FarmPlacement placement;
        private final VillageFarmSiteSelector.VillageSpatialCache spatialCache;
        private PlacedFarmInfo placed;

        private PlannedFarm(FarmPlacement placement,
                            VillageFarmSiteSelector.VillageSpatialCache spatialCache) {
            this.placement = placement;
            this.spatialCache = spatialCache;
        }

        @Override public ResourceLocation providerId() { return ID; }
        @Override public VillageBuildingImportance importance() { return VillageBuildingImportance.NORMAL; }

        @Override
        public BoundingBox reservationBox() {
            return new BoundingBox(placement.origin().getX(), Integer.MIN_VALUE / 2,
                    placement.origin().getZ(),
                    placement.origin().getX() + placement.footprintX() - 1,
                    Integer.MAX_VALUE / 2,
                    placement.origin().getZ() + placement.footprintZ() - 1);
        }

        @Override
        public boolean place(VillageGenerationContext context) {
            placed = placer.place(context.level(), placement, context.biomeType(),
                    context.chunkLoadBudget(), spatialCache);
            return placed != null;
        }

        @Override
        public List<VillageRoadPathGenerator.PlannedPath> pathsAfterPlacement(
                VillageGenerationContext context) {
            if (placed == null || !Config.outskirtFarmsPathsEnabled.get()) {
                return List.of();
            }
            VillageRoadPathGenerator.PlannedPath path = pathGenerator.planPath(
                    context.level(), placed, context.structureCenter(), context.pieces(),
                    context.biomeType(), context.preparedRoadsWithReservations(),
                    context.chunkLoadBudget());
            return path == null ? List.of() : List.of(path);
        }
    }
}
