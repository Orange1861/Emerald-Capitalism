package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrain;
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
            roadPathGenerator.place(level, planPath(VillageTerrain.live(level), farm, villageCenter, pieces,
                    biomeType, preparedRoads, new ChunkLoadBudget()));
        } catch (Exception e) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Failed to generate path from farm at ({}, {}, {}): {}",
                    farm.originX(), farm.placementY(), farm.originZ(), e.getMessage(), e);
        }
    }

    /**
     * Plans a farm connector for the shared pipeline without mutating the world.
     * Farms are often cut into a slope, sit beside water or touch a house, so one
     * search starts from entrances along every edge at once and aims for the
     * streets nearest the farm.
     */
    @Nullable
    public VillageRoadPathGenerator.PlannedPath planPath(
            VillageTerrain terrain, PlacedFarmInfo farm, BlockPos villageCenter,
            List<StructurePiece> pieces, String biomeType,
            VillageRoadPathGenerator.PreparedVillageRoads preparedRoads,
            ChunkLoadBudget loadBudget) {
        List<FarmEntrance> entrances = farmEntrances(farm, villageCenter);
        List<VillageRoadPathGenerator.ConnectionEntrance> connectionEntrances = entrances.stream()
                .map(entrance -> new VillageRoadPathGenerator.ConnectionEntrance(
                        entrance.start(), entrance.direction()))
                .toList();
        BlockPos farmCenter = new BlockPos(farm.originX() + farm.footprintX() / 2, farm.placementY(),
                farm.originZ() + farm.footprintZ() / 2);
        return roadPathGenerator.planFarmConnection(terrain, connectionEntrances, farmCenter, pieces,
                biomeType, preparedRoads, loadBudget);
    }

    /** Spacing of the entrances along each farm edge. */
    private static final int ENTRANCE_SPACING = 3;

    /**
     * Entrances just outside the farm along every edge, starting with each edge's
     * midpoint, ordered by distance to the village center. The order only breaks
     * ties; the search considers every entrance at once.
     */
    static List<FarmEntrance> farmEntrances(PlacedFarmInfo farm, BlockPos villageCenter) {
        int minX = farm.originX();
        int minZ = farm.originZ();
        int maxX = minX + farm.footprintX() - 1;
        int maxZ = minZ + farm.footprintZ() - 1;
        int midX = minX + farm.footprintX() / 2;
        int midZ = minZ + farm.footprintZ() / 2;
        int y = farm.placementY();

        List<FarmEntrance> entrances = new ArrayList<>();
        for (int z : edgePositions(minZ, maxZ, midZ)) {
            entrances.add(new FarmEntrance(new BlockPos(maxX + 1, y, z), Direction.EAST));
            entrances.add(new FarmEntrance(new BlockPos(minX - 1, y, z), Direction.WEST));
        }
        for (int x : edgePositions(minX, maxX, midX)) {
            entrances.add(new FarmEntrance(new BlockPos(x, y, maxZ + 1), Direction.SOUTH));
            entrances.add(new FarmEntrance(new BlockPos(x, y, minZ - 1), Direction.NORTH));
        }
        // Stable sort: equal distances keep the fixed edge order above.
        entrances.sort(Comparator.comparingLong(entrance -> horizontalDistanceSq(
                entrance.start(), villageCenter)));
        return entrances;
    }

    /** The midpoint first, then every {@link #ENTRANCE_SPACING} blocks outward along the edge. */
    private static List<Integer> edgePositions(int min, int max, int mid) {
        List<Integer> positions = new ArrayList<>();
        positions.add(mid);
        for (int offset = ENTRANCE_SPACING; mid - offset >= min + 1 || mid + offset <= max - 1;
             offset += ENTRANCE_SPACING) {
            if (mid - offset >= min + 1) {
                positions.add(mid - offset);
            }
            if (mid + offset <= max - 1) {
                positions.add(mid + offset);
            }
        }
        return positions;
    }

    private static long horizontalDistanceSq(BlockPos from, BlockPos to) {
        long dx = (long) to.getX() - from.getX();
        long dz = (long) to.getZ() - from.getZ();
        return dx * dx + dz * dz;
    }

    record FarmEntrance(BlockPos start, Direction direction) {
    }
}
