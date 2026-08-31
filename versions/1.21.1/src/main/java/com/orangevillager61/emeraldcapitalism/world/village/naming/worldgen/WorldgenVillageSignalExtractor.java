package com.orangevillager61.emeraldcapitalism.world.village.naming.worldgen;

import com.orangevillager61.emeraldcapitalism.world.village.JobSiteEntry;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignal;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignalSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.EnumSet;
import java.util.Set;

public final class WorldgenVillageSignalExtractor {

    private static final Set<VillageSignal> EXPLICITLY_UNIMPLEMENTED_SIGNALS = Set.of(
            VillageSignal.HOSTILE_STRUCTURE_NEARBY,
            VillageSignal.USEFUL_STRUCTURE_NEARBY,
            VillageSignal.DANGEROUS_RUIN_PROXIMITY,
            VillageSignal.MEMORY_STRUCTURE_PROXIMITY);

    public VillageSignalSnapshot extract(ServerLevel level, VillageRecord village) {
        AABB bounds = village.getBoundingBox();
        BlockPos bell = village.getBellPosition();

        int[] bedCounts = village.countBeds(level);
        List<JobSiteEntry> jobSites = village.getJobSites();
        int villagerCount = level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, bounds.inflate(16.0)).size();
        int golemCount = level.getEntitiesOfClass(IronGolem.class, bounds.inflate(24.0)).size();

        int farmer = 0;
        int smithing = 0;
        int mason = 0;
        int lectern = 0;
        int brewing = 0;
        int cartography = 0;
        int fletching = 0;
        int loom = 0;
        int barrel = 0;

        for (JobSiteEntry jobSite : jobSites) {
            String type = jobSite.jobType();
            if ("Farmer".equals(type)) farmer++;
            if ("Toolsmith".equals(type) || "Weaponsmith".equals(type) || "Armorer".equals(type)) smithing++;
            if ("Mason".equals(type)) mason++;
            if ("Librarian".equals(type)) lectern++;
            if ("Cleric".equals(type)) brewing++;
            if ("Cartographer".equals(type)) cartography++;
            if ("Fletcher".equals(type)) fletching++;
            if ("Shepherd".equals(type)) loom++;
            if ("Fisherman".equals(type)) barrel++;
        }

        double compactness = estimateCompactness(bounds, bedCounts[0] + jobSites.size() + village.getFarmlandRegistry().size());
        double spread = 1.0 - compactness;
        double routeConnectivity = estimatePathConnectivity(level, bounds);

        double localHeightVariation = estimateLocalHeightVariation(level, bell);
        double elevationExposure = normalize(bell.getY(), 52.0, 98.0);
        double basinShelter = 1.0 - elevationExposure;

        boolean isDesert = level.getBiome(bell).is(Biomes.DESERT);
        boolean isPlains = level.getBiome(bell).is(Biomes.PLAINS);
        boolean isSavanna = level.getBiome(bell).is(Biomes.SAVANNA);
        boolean isSnowy = level.getBiome(bell).is(Biomes.SNOWY_PLAINS) || level.getBiome(bell).is(Biomes.SNOWY_TAIGA);
        boolean isTaiga = level.getBiome(bell).is(Biomes.TAIGA) || level.getBiome(bell).is(Biomes.OLD_GROWTH_PINE_TAIGA);
        boolean isSwamp = level.getBiome(bell).is(Biomes.SWAMP) || level.getBiome(bell).is(Biomes.MANGROVE_SWAMP);

        double waterAdjacency = estimateWaterAdjacency(level, bounds);
        double farmlandScore = normalize(village.getFarmlandRegistry().size(), 0.0, 140.0);
        double professionDensity = normalize(jobSites.size(), 0.0, 30.0);

        VillageSignalSnapshot.Builder builder = VillageSignalSnapshot.builder()
                .with(VillageSignal.VILLAGER_COUNT, normalize(villagerCount, 0.0, 40.0))
                .with(VillageSignal.BED_COUNT, normalize(bedCounts[0], 0.0, 40.0))
                .with(VillageSignal.HOUSING_COUNT, normalize(Math.max(1, bedCounts[0] / 2.0), 0.0, 20.0))
                .with(VillageSignal.FARMER_POI_COUNT, normalize(farmer, 0.0, 12.0))
                .with(VillageSignal.FARMLAND_COUNT, farmlandScore)
                .with(VillageSignal.COMPOSTER_COUNT, normalize(farmer, 0.0, 12.0))
                .with(VillageSignal.SMITHING_POI_COUNT, normalize(smithing, 0.0, 6.0))
                .with(VillageSignal.MASON_POI_COUNT, normalize(mason, 0.0, 4.0))
                .with(VillageSignal.LECTERN_COUNT, normalize(lectern, 0.0, 5.0))
                .with(VillageSignal.BREWING_COUNT, normalize(brewing, 0.0, 4.0))
                .with(VillageSignal.CARTOGRAPHY_COUNT, normalize(cartography, 0.0, 4.0))
                .with(VillageSignal.FLETCHING_COUNT, normalize(fletching, 0.0, 4.0))
                .with(VillageSignal.LOOM_COUNT, normalize(loom, 0.0, 4.0))
                .with(VillageSignal.BARREL_COUNT, normalize(barrel, 0.0, 6.0))
                .with(VillageSignal.BELL_CENTER_STRENGTH, 1.0)
                .with(VillageSignal.PATH_CONNECTEDNESS, routeConnectivity)
                .with(VillageSignal.LAYOUT_COMPACTNESS, compactness)
                .with(VillageSignal.SETTLEMENT_SPREAD, spread)
                .with(VillageSignal.ROUTE_CONNECTIVITY, routeConnectivity)
                .with(VillageSignal.RIVER_ADJACENT, waterAdjacency)
                .with(VillageSignal.COAST_ADJACENT, waterAdjacency > 0.45 ? 1.0 : 0.0)
                .with(VillageSignal.WETLAND, isSwamp ? 1.0 : 0.0)
                .with(VillageSignal.ROCKY_TERRAIN, estimateRockyTerrain(level, bounds))
                .with(VillageSignal.ELEVATION_EXPOSURE, elevationExposure)
                .with(VillageSignal.BASIN_SHELTER, basinShelter)
                .with(VillageSignal.LOCAL_HEIGHT_VARIATION, localHeightVariation)
                .with(VillageSignal.GOLEM_COUNT, normalize(golemCount, 0.0, 4.0))
                .with(VillageSignal.HOSTILE_STRUCTURE_NEARBY, 0.0)
                .with(VillageSignal.USEFUL_STRUCTURE_NEARBY, 0.0)
                .with(VillageSignal.REMOTE_ISOLATED, routeConnectivity < 0.15 ? 1.0 : 0.0)
                .with(VillageSignal.DANGEROUS_RUIN_PROXIMITY, 0.0)
                .with(VillageSignal.MEMORY_STRUCTURE_PROXIMITY, 0.0)
                .with(VillageSignal.KNOWLEDGE_POI_DENSITY, normalize(lectern + brewing + cartography, 0.0, 10.0))
                .with(VillageSignal.PRODUCTION_POI_DENSITY, professionDensity)
                .with(VillageSignal.DESERT, isDesert ? 1.0 : 0.0)
                .with(VillageSignal.PLAINS, isPlains ? 1.0 : 0.0)
                .with(VillageSignal.SAVANNA, isSavanna ? 1.0 : 0.0)
                .with(VillageSignal.SNOWY, isSnowy ? 1.0 : 0.0)
                .with(VillageSignal.TAIGA, isTaiga ? 1.0 : 0.0)
                .with(VillageSignal.SWAMP, isSwamp ? 1.0 : 0.0);

        return validateSnapshot(builder.build());
    }

    static Set<VillageSignal> explicitlyUnimplementedSignals() {
        return EXPLICITLY_UNIMPLEMENTED_SIGNALS;
    }

    static VillageSignalSnapshot validateSnapshot(VillageSignalSnapshot snapshot) {
        if (!snapshot.all().keySet().equals(EnumSet.allOf(VillageSignal.class))) {
            throw new IllegalStateException("Village naming signal extraction omitted a signal");
        }
        if (snapshot.all().values().stream()
                .anyMatch(value -> !Double.isFinite(value) || value < 0.0 || value > 1.0)) {
            throw new IllegalStateException("Village naming signal extraction produced an unnormalized signal");
        }
        return snapshot;
    }

    private double estimateCompactness(AABB bounds, int pointCount) {
        double volume = Math.max(1.0, bounds.getXsize() * bounds.getYsize() * bounds.getZsize());
        return normalize((pointCount * 250.0) / volume, 0.0, 1.0);
    }

    private double estimatePathConnectivity(ServerLevel level, AABB bounds) {
        int minX = (int) Math.floor(bounds.minX);
        int minY = (int) Math.floor(bounds.minY);
        int minZ = (int) Math.floor(bounds.minZ);
        int maxX = (int) Math.ceil(bounds.maxX);
        int maxY = (int) Math.ceil(bounds.maxY);
        int maxZ = (int) Math.ceil(bounds.maxZ);

        int totalSamples = 0;
        int pathSamples = 0;
        int step = 4;

        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                if (y < minY || y > maxY + 8) {
                    continue;
                }
                totalSamples++;
                BlockPos pos = new BlockPos(x, y - 1, z);
                if (level.getBlockState(pos).is(Blocks.DIRT_PATH)) {
                    pathSamples++;
                }
            }
        }

        if (totalSamples == 0) {
            return 0.0;
        }
        return normalize((double) pathSamples / totalSamples, 0.0, 0.35);
    }

    private double estimateWaterAdjacency(ServerLevel level, AABB bounds) {
        int minX = (int) Math.floor(bounds.minX);
        int minZ = (int) Math.floor(bounds.minZ);
        int maxX = (int) Math.ceil(bounds.maxX);
        int maxZ = (int) Math.ceil(bounds.maxZ);

        int total = 0;
        int water = 0;
        int step = 6;

        for (int x = minX - 16; x <= maxX + 16; x += step) {
            for (int z = minZ - 16; z <= maxZ + 16; z += step) {
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos pos = new BlockPos(x, y - 1, z);
                total++;
                if (level.getFluidState(pos).is(FluidTags.WATER)) {
                    water++;
                }
            }
        }

        return total == 0 ? 0.0 : normalize((double) water / total, 0.0, 0.45);
    }

    private double estimateLocalHeightVariation(ServerLevel level, BlockPos center) {
        int radius = 24;
        int step = 6;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int x = center.getX() - radius; x <= center.getX() + radius; x += step) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z += step) {
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        if (minY == Integer.MAX_VALUE) {
            return 0.0;
        }
        return normalize(maxY - minY, 0.0, 20.0);
    }

    private double estimateRockyTerrain(ServerLevel level, AABB bounds) {
        int minX = (int) Math.floor(bounds.minX);
        int minZ = (int) Math.floor(bounds.minZ);
        int maxX = (int) Math.ceil(bounds.maxX);
        int maxZ = (int) Math.ceil(bounds.maxZ);

        int total = 0;
        int rocky = 0;
        int step = 5;

        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos pos = new BlockPos(x, y - 1, z);
                total++;
                if (level.getBlockState(pos).is(Blocks.STONE) || level.getBlockState(pos).is(Blocks.COBBLESTONE)) {
                    rocky++;
                }
            }
        }

        return total == 0 ? 0.0 : normalize((double) rocky / total, 0.0, 0.4);
    }

    private static double normalize(double value, double min, double max) {
        if (max <= min) {
            return 0.0;
        }
        double normalized = (value - min) / (max - min);
        return Math.max(0.0, Math.min(1.0, normalized));
    }
}
