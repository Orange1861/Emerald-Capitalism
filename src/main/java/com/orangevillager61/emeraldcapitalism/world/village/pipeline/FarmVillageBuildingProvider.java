package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrain;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingImportance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.FarmPlacement;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.PlacedFarmInfo;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmPathGenerator;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmPlacer;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.JournaledFarmTask;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSavedData;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSiteSelector;
import net.minecraft.core.BlockPos;
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
        BlockPos center = context.structureCenter();
        if (context.farmSavedData().hasFarmJournal(center)) {
            return replayJournaledFarms(context, center, cache);
        }
        List<FarmPlacement> sites = context.farmSiteSelector().findSites(
                context.level(), context.structureCenter(), context.biomeType(),
                context.villageBox(), context.pieces(), context.chunkLoadBudget(), cache);
        List<PlannedFarm> result = new ArrayList<>(sites.size());
        for (FarmPlacement site : sites) {
            result.add(new PlannedFarm(site, sites, center, cache));
        }
        return result;
    }

    /**
     * Loads the farm templates on the server thread, then selects sites on the
     * planning worker against the village's terrain snapshot. The spatial cache
     * is only read and filled by this job until it finishes.
     */
    @Override
    public VillageBuildingPlanningTask startPlanning(VillageGenerationContext context) {
        if (!Config.outskirtFarmsEnabled.get()
                || context.farmSavedData().areFarmsPlaced(context.structureCenter())) {
            return VillageBuildingPlanningTask.completed(List.of());
        }
        if (context.farmSavedData().hasFarmJournal(context.structureCenter())) {
            // An earlier run planned this village's farms and did not finish them. Replay those
            // exact sites: terrain may already be part-modified there, and a fresh site search
            // would pick somewhere else and leave the scar behind.
            return VillageBuildingPlanningTask.completed(replayJournaledFarms(
                    context, context.structureCenter(), context.spatialCacheWithReservations()));
        }
        VillageFarmSiteSelector selector = context.farmSiteSelector();
        VillageFarmSiteSelector.SiteSearch search = selector.prepareSiteSearch(
                context.level(), context.structureCenter(), context.biomeType());
        if (search == null) {
            return VillageBuildingPlanningTask.completed(List.of());
        }
        VillageFarmSiteSelector.VillageSpatialCache cache = context.spatialCacheWithReservations();
        BlockPos center = context.structureCenter();
        String biomeType = context.biomeType();
        BoundingBox villageBox = context.villageBox();
        List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = context.pieces();
        return VillagePlanningWorker.planningTask(context, "Farm", terrain -> {
            List<FarmPlacement> sites = selector.findSites(terrain, search, center, biomeType,
                    villageBox, pieces, cache);
            List<PlannedFarm> planned = new ArrayList<>(sites.size());
            for (FarmPlacement site : sites) {
                planned.add(new PlannedFarm(site, sites, center, cache));
            }
            return planned;
        });
    }

    /** The journaled farms that still need placing, at their original sites. */
    private List<PlannedFarm> replayJournaledFarms(
            VillageGenerationContext context, BlockPos center,
            VillageFarmSiteSelector.VillageSpatialCache cache) {
        List<FarmPlacement> pending = context.farmSavedData().farmsToPlace(center);
        List<PlannedFarm> planned = new ArrayList<>(pending.size());
        for (FarmPlacement site : pending) {
            planned.add(new PlannedFarm(site, pending, center, cache));
        }
        if (!pending.isEmpty()) {
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Retrying {} unfinished farm(s) for village {} at their saved sites",
                    pending.size(), center);
        }
        return planned;
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
        /** Every site planned together with this one; journaled before the first placement. */
        private final List<FarmPlacement> plannedSet;
        private final BlockPos villageCenter;
        private final VillageFarmSiteSelector.VillageSpatialCache spatialCache;
        private PlacedFarmInfo placed;
        private VillageFarmPlacer.FarmPlacementTask placementTask;

        private PlannedFarm(FarmPlacement placement, List<FarmPlacement> plannedSet, BlockPos villageCenter,
                            VillageFarmSiteSelector.VillageSpatialCache spatialCache) {
            this.placement = placement;
            this.plannedSet = List.copyOf(plannedSet);
            this.villageCenter = villageCenter;
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
            JournaledFarmTask task = journaledTask(context);
            while (!task.processStep()) {
                // Synchronous callers run the same journaled steps without yielding.
            }
            placementTask = task.placement();
            placed = placementTask.result();
            return placed != null;
        }

        private JournaledFarmTask journaledTask(VillageGenerationContext context) {
            VillageFarmSavedData farms = context.farmSavedData();
            return JournaledFarmTask.begin(farms, villageCenter, plannedSet, placement,
                    placer.createPlacementTask(context.level(), placement, context.biomeType(),
                            context.chunkLoadBudget(), spatialCache),
                    () -> farms.flushNow(context.level()));
        }

        @Override
        public VillageBuildingPlacementTask startPlacement(VillageGenerationContext context) {
            JournaledFarmTask task = journaledTask(context);
            placementTask = task.placement();
            return task;
        }

        @Override
        public PathPlanningJob pathPlanningJob(VillageGenerationContext context) {
            if (placementTask != null) {
                placed = placementTask.result();
            }
            if (placed == null || !Config.outskirtFarmsPathsEnabled.get()) {
                return terrain -> List.of();
            }
            PlacedFarmInfo farm = placed;
            BlockPos center = context.structureCenter();
            List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = context.pieces();
            String biomeType = context.biomeType();
            VillageRoadPathGenerator.PreparedVillageRoads roads = context.preparedRoadsWithReservations();
            return terrain -> {
                VillageRoadPathGenerator.PlannedPath path = pathGenerator.planPath(
                        terrain, farm, center, pieces, biomeType, roads, null);
                return path == null ? List.of() : List.of(path);
            };
        }
    }
}
