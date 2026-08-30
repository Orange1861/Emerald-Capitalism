package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import javax.annotation.Nullable;

import java.util.List;

/** Connects placed outskirt farms to the village's actual street network. */
public final class VillageFarmPathGenerator {

    private final VillageRoadPathGenerator roadPathGenerator = new VillageRoadPathGenerator();

    public VillageRoadPathGenerator.PreparedVillageRoads prepare(
            ServerLevel level, List<StructurePiece> pieces) {
        return roadPathGenerator.prepare(level, pieces);
    }

    /**
     * Generates a routed path from the farm edge facing the village to a real
     * street surface. The bank road planner supplies terrain checks, building
     * avoidance, biome-specific materials, and bounded chunk loading.
     */
    public void generatePath(ServerLevel level, PlacedFarmInfo farm, BlockPos villageCenter,
                             List<StructurePiece> pieces, String biomeType,
                             VillageRoadPathGenerator.PreparedVillageRoads preparedRoads) {
        try {
            roadPathGenerator.place(level, planPath(level, farm, villageCenter, pieces,
                    biomeType, preparedRoads, new ChunkLoadBudget()));
        } catch (Exception e) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Failed to generate path from farm at ({}, {}, {}): {}",
                    farm.originX(), farm.placementY(), farm.originZ(), e.getMessage(), e);
        }
    }

    /** Plans a farm connector for the shared pipeline without mutating the world. */
    @Nullable
    public VillageRoadPathGenerator.PlannedPath planPath(
            ServerLevel level, PlacedFarmInfo farm, BlockPos villageCenter,
            List<StructurePiece> pieces, String biomeType,
            VillageRoadPathGenerator.PreparedVillageRoads preparedRoads,
            ChunkLoadBudget loadBudget) {
        FarmEntrance entrance = findFarmEntrance(farm, villageCenter);
        return roadPathGenerator.planFarmConnection(level, entrance.start(), villageCenter,
                pieces, biomeType, entrance.direction(), preparedRoads, loadBudget);
    }

    /** Returns the first block outside the farm edge that faces the village. */
    private FarmEntrance findFarmEntrance(PlacedFarmInfo farm, BlockPos villageCenter) {
        int minX = farm.originX();
        int minZ = farm.originZ();
        int maxXExclusive = minX + farm.footprintX();
        int maxZExclusive = minZ + farm.footprintZ();
        int midX = minX + farm.footprintX() / 2;
        int midZ = minZ + farm.footprintZ() / 2;

        int dx = villageCenter.getX() - midX;
        int dz = villageCenter.getZ() - midZ;
        if (Math.abs(dx) > Math.abs(dz)) {
            if (dx > 0) {
                return new FarmEntrance(
                        new BlockPos(maxXExclusive, farm.placementY(), midZ), Direction.EAST);
            }
            return new FarmEntrance(
                    new BlockPos(minX - 1, farm.placementY(), midZ), Direction.WEST);
        }

        if (dz > 0) {
            return new FarmEntrance(
                    new BlockPos(midX, farm.placementY(), maxZExclusive), Direction.SOUTH);
        }
        return new FarmEntrance(
                new BlockPos(midX, farm.placementY(), minZ - 1), Direction.NORTH);
    }

    private record FarmEntrance(BlockPos start, Direction direction) {
    }
}
