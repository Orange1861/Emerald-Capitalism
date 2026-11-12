package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Plans a farm connector for the shared pipeline without mutating the world.
     * The edge facing the village is tried first. Farms are often cut into a
     * slope or sit beside water, so the remaining edges are tried in order of
     * their distance to the village before the farm is left unconnected.
     */
    @Nullable
    public VillageRoadPathGenerator.PlannedPath planPath(
            ServerLevel level, PlacedFarmInfo farm, BlockPos villageCenter,
            List<StructurePiece> pieces, String biomeType,
            VillageRoadPathGenerator.PreparedVillageRoads preparedRoads,
            ChunkLoadBudget loadBudget) {
        List<FarmEntrance> entrances = farmEntrances(farm, villageCenter);
        // Every edge shares one planning deadline; once it passes, the remaining
        // edges are skipped so one farm stays within a single path's tick budget.
        long deadline = VillageRoadPathGenerator.newPlanningDeadline();
        for (int i = 0; i < entrances.size(); i++) {
            if (i > 0 && System.nanoTime() > deadline) {
                break;
            }
            FarmEntrance entrance = entrances.get(i);
            VillageRoadPathGenerator.PlannedPath path = roadPathGenerator.planFarmConnection(
                    level, entrance.start(), villageCenter, pieces, biomeType,
                    entrance.direction(), preparedRoads, loadBudget, false, deadline);
            if (path != null) {
                return path;
            }
        }
        EmeraldCapitalism.LOGGER.warn(
                "[ECAP] Could not plan a complete farm path from {} to a reachable village street",
                entrances.getFirst().start());
        return null;
    }

    /** Edge midpoints just outside the farm, ordered by distance to the village center. */
    static List<FarmEntrance> farmEntrances(PlacedFarmInfo farm, BlockPos villageCenter) {
        int minX = farm.originX();
        int minZ = farm.originZ();
        int maxXExclusive = minX + farm.footprintX();
        int maxZExclusive = minZ + farm.footprintZ();
        int midX = minX + farm.footprintX() / 2;
        int midZ = minZ + farm.footprintZ() / 2;
        int y = farm.placementY();

        List<FarmEntrance> entrances = new ArrayList<>(4);
        entrances.add(new FarmEntrance(new BlockPos(maxXExclusive, y, midZ), Direction.EAST));
        entrances.add(new FarmEntrance(new BlockPos(minX - 1, y, midZ), Direction.WEST));
        entrances.add(new FarmEntrance(new BlockPos(midX, y, maxZExclusive), Direction.SOUTH));
        entrances.add(new FarmEntrance(new BlockPos(midX, y, minZ - 1), Direction.NORTH));
        // Stable sort: equal distances keep the fixed edge order above.
        entrances.sort(Comparator.comparingLong(entrance -> horizontalDistanceSq(
                entrance.start(), villageCenter)));
        return entrances;
    }

    private static long horizontalDistanceSq(BlockPos from, BlockPos to) {
        long dx = (long) to.getX() - from.getX();
        long dz = (long) to.getZ() - from.getZ();
        return dx * dx + dz * dz;
    }

    record FarmEntrance(BlockPos start, Direction direction) {
    }
}
