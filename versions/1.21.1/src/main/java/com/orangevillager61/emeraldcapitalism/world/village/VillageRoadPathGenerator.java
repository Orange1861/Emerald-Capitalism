package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.ChunkLoadBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Plans and places bounded-width connections from generated structure
 * entrances to actual surface blocks in the village street network.
 */
public final class VillageRoadPathGenerator {

    private static final ConnectionProfile BANK_PROFILE =
            new ConnectionProfile("Bank", 4, 4, 128, 160);
    private static final ConnectionProfile FARM_PROFILE =
            new ConnectionProfile("Farm", 3, 0, 64, 64);
    private static final ConnectionProfile LIBRARY_PROFILE =
            new ConnectionProfile("Library", 3, 3, 96, 96);
    private static final ConnectionProfile LUMBERMILL_PROFILE =
            new ConnectionProfile("Lumbermill", 3, 0, 96, 96);
    private static final int MAX_VISITED_NODES = 20_000;
    private static final int SEARCH_MARGIN = 12;
    private static final int MAX_TARGET_ATTEMPTS = 24;
    private static final int MIN_TARGET_SPACING_SQ = 9;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    public void generate(ServerLevel level, BlockPos pathStart, BlockPos pathTargetHint,
                         List<StructurePiece> villagePieces, String biomeType,
                         Direction entranceDirection) {
        generate(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, null);
    }

    /** Applies a bank connector while avoiding the bank footprint itself. */
    public void generate(ServerLevel level, BlockPos pathStart, BlockPos pathTargetHint,
                         List<StructurePiece> villagePieces, String biomeType,
                         Direction entranceDirection, @Nullable PreparedVillageRoads preparedRoads) {
        place(level, planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, BANK_PROFILE, preparedRoads, null));
    }

    /**
     * Connects an ungraded surface entrance, such as an outskirt farm edge, to
     * the same real village street network used by generated banks.
     */
    public void generateSurfaceConnection(ServerLevel level, BlockPos pathStart,
                                          BlockPos pathTargetHint,
                                          List<StructurePiece> villagePieces,
                                          String biomeType,
                                          Direction entranceDirection) {
        place(level, planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, FARM_PROFILE, null, null));
    }

    /** Reuses the stable village street and building snapshot across farm paths. */
    public void generateSurfaceConnection(ServerLevel level, BlockPos pathStart,
                                          BlockPos pathTargetHint,
                                          List<StructurePiece> villagePieces,
                                          String biomeType,
                                          Direction entranceDirection,
                                          PreparedVillageRoads preparedRoads) {
        place(level, planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, FARM_PROFILE, preparedRoads, null));
    }

    /** Plans, but does not place, the important four-wide bank connector. */
    @Nullable
    public PlannedPath planBankConnection(ServerLevel level, BlockPos pathStart,
                                          BlockPos pathTargetHint,
                                          List<StructurePiece> villagePieces,
                                          String biomeType,
                                          Direction entranceDirection,
                                          PreparedVillageRoads preparedRoads,
                                          ChunkLoadBudget sharedLoadBudget) {
        return planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, BANK_PROFILE, preparedRoads, sharedLoadBudget);
    }

    /** Plans, but does not place, a compact farm connector. */
    @Nullable
    public PlannedPath planFarmConnection(ServerLevel level, BlockPos pathStart,
                                          BlockPos pathTargetHint,
                                          List<StructurePiece> villagePieces,
                                          String biomeType,
                                          Direction entranceDirection,
                                          PreparedVillageRoads preparedRoads,
                                          ChunkLoadBudget sharedLoadBudget) {
        return planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, FARM_PROFILE, preparedRoads, sharedLoadBudget);
    }

    /** Plans a compact library connector using the same bounded street search as farms. */
    @Nullable
    public PlannedPath planLibraryConnection(ServerLevel level, BlockPos pathStart,
                                             BlockPos pathTargetHint,
                                             List<StructurePiece> villagePieces,
                                             String biomeType,
                                             Direction entranceDirection,
                                             PreparedVillageRoads preparedRoads,
                                             ChunkLoadBudget sharedLoadBudget) {
        return planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, LIBRARY_PROFILE, preparedRoads, sharedLoadBudget);
    }

    /** Plans a compact lumbermill connector from its authored or inferred entrance. */
    @Nullable
    public PlannedPath planLumbermillConnection(ServerLevel level, BlockPos pathStart,
                                                BlockPos pathTargetHint,
                                                List<StructurePiece> villagePieces,
                                                String biomeType,
                                                Direction entranceDirection,
                                                PreparedVillageRoads preparedRoads,
                                                ChunkLoadBudget sharedLoadBudget) {
        return planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, LUMBERMILL_PROFILE, preparedRoads, sharedLoadBudget);
    }

    /** Applies a previously reserved plan after every village building is in place. */
    public void place(ServerLevel level, @Nullable PlannedPath path) {
        if (path == null) {
            return;
        }
        applyPlacementPlan(level, path.cells, path.pathBlocks);
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP] Generated {}-wide {} path with {} surface blocks from {} "
                        + "to village street at {} using {}",
                path.pathWidth, path.connectionType.toLowerCase(Locale.ROOT),
                path.cells.size(), path.pathStart, path.target, path.pathBlocks.surfaceBlock());
    }

    /**
     * Captures actual street surface columns and a chunk-indexed building map.
     * The result is intended to live only for one village-generation task.
     */
    public PreparedVillageRoads prepare(ServerLevel level, List<StructurePiece> villagePieces) {
        SurfaceCache surfaceCache = new SurfaceCache(level);
        Map<Long, StreetCell> streetByColumn = new HashMap<>();
        Map<Long, List<BoundingBox>> buildingsByChunk = new HashMap<>();

        for (StructurePiece piece : villagePieces) {
            BoundingBox box = piece.getBoundingBox();
            if (isPathPiece(piece)) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        SurfaceSample sample = surfaceCache.get(x, z);
                        if (sample != null && isStreetSurface(sample.state().getBlock())) {
                            streetByColumn.putIfAbsent(columnKey(x, z),
                                    new StreetCell(sample.pos()));
                        }
                    }
                }
                continue;
            }

            for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
                for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                    buildingsByChunk.computeIfAbsent(columnKey(chunkX, chunkZ), ignored -> new ArrayList<>())
                            .add(box);
                }
            }
        }

        buildingsByChunk.replaceAll((ignored, boxes) -> List.copyOf(boxes));
        return new PreparedVillageRoads(streetByColumn, buildingsByChunk);
    }

    @Nullable
    private PlannedPath planConnection(ServerLevel level, BlockPos pathStart,
                                       BlockPos pathTargetHint,
                                       List<StructurePiece> villagePieces,
                                       String biomeType,
                                       Direction entranceDirection,
                                       ConnectionProfile profile,
                                       @Nullable PreparedVillageRoads preparedRoads,
                                       @Nullable ChunkLoadBudget sharedLoadBudget) {
        if (!entranceDirection.getAxis().isHorizontal()) {
            return null;
        }

        PreparedVillageRoads roads = preparedRoads != null
                ? preparedRoads
                : prepare(level, villagePieces);
        SurfaceCache surfaceCache = new SurfaceCache(level);
        List<StreetTarget> streetTargets = findStreetTargets(
                level, pathStart, roads.streetByColumn(), profile.maxTargetDistance());
        if (streetTargets.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] {} path at {} found no actual street surface near target hint {}",
                    profile.connectionType(), pathStart, pathTargetHint);
            return null;
        }

        List<BlockPos> entrancePrefix = profile.fixedEntranceRows() > 0
                ? buildEntrancePrefix(level, pathStart, entranceDirection, profile.fixedEntranceRows())
                : buildSurfaceEntrance(level, pathStart);
        if (entrancePrefix.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] {} path entrance chunk is unavailable at {}",
                    profile.connectionType(), pathStart);
            return null;
        }

        BlockPos searchStart = entrancePrefix.get(entrancePrefix.size() - 1);
        ChunkLoadBudget loadBudget = sharedLoadBudget != null
                ? sharedLoadBudget : new ChunkLoadBudget();
        int targetAttempts = Math.min(MAX_TARGET_ATTEMPTS, streetTargets.size());

        for (int targetIndex = 0; targetIndex < targetAttempts; targetIndex++) {
            StreetTarget streetTarget = streetTargets.get(targetIndex);
            if (!ensureSearchChunksLoaded(level, searchStart, streetTarget.pos(),
                    loadBudget, profile.pathWidth())) {
                continue;
            }

            surfaceCache.clear();
            List<BlockPos> route = findRoute(level, searchStart, streetTarget.pos(), entranceDirection,
                    roads, surfaceCache, profile.pathWidth(), profile.maxRouteSteps());
            if (route.isEmpty()) {
                continue;
            }

            List<BlockPos> completeRoute = new ArrayList<>(entrancePrefix);
            completeRoute.addAll(route.subList(1, route.size()));
            VillagePathBlocks pathBlocks = VillagePathBlocks.matchingStreet(
                    streetTarget.surfaceBlock(), biomeType);
            List<PlannedPathCell> placementPlan = createPlacementPlan(
                    level, completeRoute, profile.fixedEntranceRows(), entranceDirection,
                    roads, surfaceCache, profile.pathWidth());
            if (placementPlan.isEmpty()) {
                continue;
            }

            return new PlannedPath(List.copyOf(placementPlan), pathBlocks,
                    profile.connectionType(), profile.pathWidth(), pathStart, streetTarget.pos());
        }

        EmeraldCapitalism.LOGGER.warn(
                "[ECAP] Could not plan a complete {} path from {} to a reachable village street",
                profile.connectionType().toLowerCase(Locale.ROOT), pathStart);
        return null;
    }

    private List<StreetTarget> findStreetTargets(ServerLevel level, BlockPos from,
                                                  Map<Long, StreetCell> streetByColumn,
                                                  int maxTargetDistance) {
        Map<Long, StreetTarget> candidatesByColumn = new HashMap<>();
        long maxDistanceSq = (long) maxTargetDistance * maxTargetDistance;

        for (Map.Entry<Long, StreetCell> entry : streetByColumn.entrySet()) {
            StreetCell cell = entry.getValue();
            Block currentSurface = level.getBlockState(cell.pos()).getBlock();
            if (!isStreetSurface(currentSurface)) {
                continue;
            }
            long distanceSq = horizontalDistanceSq(from, cell.pos().getX(), cell.pos().getZ());
            if (distanceSq > maxDistanceSq) {
                continue;
            }
            long score = distanceSq * 100L + streetSurfacePenalty(currentSurface);
            candidatesByColumn.put(entry.getKey(),
                    new StreetTarget(cell.pos(), currentSurface, score));
        }

        Set<Long> mainNetwork = largestConnectedStreetNetwork(candidatesByColumn);
        List<StreetTarget> candidates = mainNetwork.stream()
                .map(candidatesByColumn::get)
                .sorted(Comparator.comparingLong(StreetTarget::score))
                .toList();
        List<StreetTarget> targets = new ArrayList<>(MAX_TARGET_ATTEMPTS);
        for (StreetTarget candidate : candidates) {
            boolean tooClose = targets.stream().anyMatch(selected ->
                    horizontalDistanceSq(selected.pos(), candidate.pos().getX(), candidate.pos().getZ())
                            < MIN_TARGET_SPACING_SQ);
            if (!tooClose) {
                targets.add(candidate);
            }
            if (targets.size() >= MAX_TARGET_ATTEMPTS) {
                break;
            }
        }
        return targets;
    }

    private Set<Long> largestConnectedStreetNetwork(Map<Long, StreetTarget> streetByColumn) {
        Set<Long> unvisited = new HashSet<>(streetByColumn.keySet());
        Set<Long> largest = Set.of();
        long largestNearestScore = Long.MAX_VALUE;

        while (!unvisited.isEmpty()) {
            long start = unvisited.iterator().next();
            List<Long> queue = new ArrayList<>();
            Set<Long> component = new HashSet<>();
            queue.add(start);
            unvisited.remove(start);
            long nearestScore = Long.MAX_VALUE;

            for (int queueIndex = 0; queueIndex < queue.size(); queueIndex++) {
                long currentKey = queue.get(queueIndex);
                StreetTarget current = streetByColumn.get(currentKey);
                component.add(currentKey);
                nearestScore = Math.min(nearestScore, current.score());

                int currentX = current.pos().getX();
                int currentZ = current.pos().getZ();
                for (Direction direction : HORIZONTAL_DIRECTIONS) {
                    long neighborKey = columnKey(
                            currentX + direction.getStepX(), currentZ + direction.getStepZ());
                    StreetTarget neighbor = streetByColumn.get(neighborKey);
                    if (neighbor == null || !unvisited.contains(neighborKey)
                            || Math.abs(neighbor.pos().getY() - current.pos().getY()) > 1) {
                        continue;
                    }
                    unvisited.remove(neighborKey);
                    queue.add(neighborKey);
                }
            }

            if (component.size() > largest.size()
                    || (component.size() == largest.size() && nearestScore < largestNearestScore)) {
                largest = component;
                largestNearestScore = nearestScore;
            }
        }
        return largest;
    }

    private List<BlockPos> buildEntrancePrefix(ServerLevel level, BlockPos pathStart,
                                                Direction entranceDirection, int rowCount) {
        List<BlockPos> prefix = new ArrayList<>(rowCount);

        for (int step = 0; step < rowCount; step++) {
            BlockPos column = pathStart.relative(entranceDirection, step);
            if (!level.hasChunk(column.getX() >> 4, column.getZ() >> 4)) {
                return List.of();
            }
            // Bank placement already grades and clears this four-row apron. Keep
            // it at that known height so the bank wall, doors, or nearby village
            // piece bounds cannot make the heightmap reject the road's launch.
            prefix.add(column);
        }
        return prefix;
    }

    private List<BlockPos> buildSurfaceEntrance(ServerLevel level, BlockPos pathStart) {
        return level.hasChunk(pathStart.getX() >> 4, pathStart.getZ() >> 4)
                ? List.of(pathStart)
                : List.of();
    }

    private List<BlockPos> findRoute(ServerLevel level, BlockPos start, BlockPos target,
                                     Direction startDirection, PreparedVillageRoads roads,
                                     SurfaceCache surfaceCache, int pathWidth,
                                     int maxRouteSteps) {
        int minX = Math.min(start.getX(), target.getX()) - SEARCH_MARGIN;
        int maxX = Math.max(start.getX(), target.getX()) + SEARCH_MARGIN;
        int minZ = Math.min(start.getZ(), target.getZ()) - SEARCH_MARGIN;
        int maxZ = Math.max(start.getZ(), target.getZ()) + SEARCH_MARGIN;

        RouteKey startKey = new RouteKey(start.getX(), start.getZ(), startDirection);
        PriorityQueue<OpenRouteNode> open = new PriorityQueue<>(
                Comparator.comparingInt(OpenRouteNode::estimatedTotalCost));
        Map<RouteKey, Integer> costs = new HashMap<>();
        Map<RouteKey, Integer> steps = new HashMap<>();
        Map<RouteKey, RouteKey> cameFrom = new HashMap<>();
        Set<RouteKey> closed = new HashSet<>();

        costs.put(startKey, 0);
        steps.put(startKey, 0);
        open.add(new OpenRouteNode(startKey, heuristic(startKey.x(), startKey.z(), target)));

        int visited = 0;
        while (!open.isEmpty() && visited < MAX_VISITED_NODES) {
            OpenRouteNode queued = open.poll();
            RouteKey current = queued.key();
            if (!closed.add(current)) {
                continue;
            }
            visited++;

            if (current.x() == target.getX() && current.z() == target.getZ()) {
                return reconstructRoute(current, cameFrom, surfaceCache);
            }

            int currentSteps = steps.getOrDefault(current, maxRouteSteps);
            if (currentSteps >= maxRouteSteps) {
                continue;
            }

            SurfaceSample currentSurface = surfaceCache.get(current.x(), current.z());
            if (currentSurface == null) {
                continue;
            }

            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                if (direction == current.incomingDirection().getOpposite()) {
                    continue;
                }

                int nextX = current.x() + direction.getStepX();
                int nextZ = current.z() + direction.getStepZ();
                if (nextX < minX || nextX > maxX || nextZ < minZ || nextZ > maxZ) {
                    continue;
                }

                SurfaceSample nextSurface = surfaceCache.get(nextX, nextZ);
                if (nextSurface == null || !isCorridorRowTraversable(level, nextSurface,
                        currentSurface, direction, roads, surfaceCache, pathWidth)) {
                    continue;
                }

                RouteKey next = new RouteKey(nextX, nextZ, direction);
                if (closed.contains(next)) {
                    continue;
                }

                int heightCost = Math.abs(nextSurface.pos().getY() - currentSurface.pos().getY()) * 5;
                int turnCost = direction == current.incomingDirection() ? 0 : 3;
                int nextCost = costs.get(current) + 10 + heightCost + turnCost;
                if (nextCost >= costs.getOrDefault(next, Integer.MAX_VALUE)) {
                    continue;
                }

                costs.put(next, nextCost);
                steps.put(next, currentSteps + 1);
                cameFrom.put(next, current);
                open.add(new OpenRouteNode(next,
                        nextCost + heuristic(nextX, nextZ, target)));
            }
        }

        return List.of();
    }

    private boolean isCorridorRowTraversable(ServerLevel level, SurfaceSample center,
                                             @Nullable SurfaceSample previousCenter,
                                             Direction travelDirection,
                                             PreparedVillageRoads roads,
                                             SurfaceCache surfaceCache,
                                             int pathWidth) {
        if (previousCenter != null
                && Math.abs(center.pos().getY() - previousCenter.pos().getY()) > 1) {
            return false;
        }

        int firstWidthOffset = -(pathWidth / 2);
        int lastWidthOffset = firstWidthOffset + pathWidth - 1;
        for (int width = firstWidthOffset; width <= lastWidthOffset; width++) {
            int x = center.pos().getX();
            int z = center.pos().getZ();
            if (travelDirection.getAxis() == Direction.Axis.X) {
                z += width;
            } else {
                x += width;
            }

            if (!level.hasChunk(x >> 4, z >> 4)
                    || roads.isInsideBuilding(x, z)) {
                return false;
            }

            SurfaceSample sample = surfaceCache.get(x, z);
            if (sample == null
                    || Math.abs(sample.pos().getY() - center.pos().getY()) > 1
                    || !isUsablePathSurface(sample.state())) {
                return false;
            }
        }
        return true;
    }

    private List<BlockPos> reconstructRoute(RouteKey end, Map<RouteKey, RouteKey> cameFrom,
                                            SurfaceCache surfaceCache) {
        List<BlockPos> reversed = new ArrayList<>();
        RouteKey current = end;
        while (current != null) {
            SurfaceSample sample = surfaceCache.get(current.x(), current.z());
            if (sample == null) {
                return List.of();
            }
            reversed.add(sample.pos());
            current = cameFrom.get(current);
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private List<PlannedPathCell> createPlacementPlan(ServerLevel level, List<BlockPos> route,
                                                       int fixedEntranceRows,
                                                       Direction entranceDirection,
                                                       PreparedVillageRoads roads,
                                                       SurfaceCache surfaceCache,
                                                       int pathWidth) {
        if (route.size() < 2) {
            return List.of();
        }

        Map<BlockPos, PlannedPathCell> cells = new LinkedHashMap<>();
        // The final center is an existing street block and remains untouched.
        for (int routeIndex = 0; routeIndex < route.size() - 1; routeIndex++) {
            BlockPos center = route.get(routeIndex);
            Direction direction = routeDirection(route, routeIndex, entranceDirection);

            int firstWidthOffset = -(pathWidth / 2);
            int lastWidthOffset = firstWidthOffset + pathWidth - 1;
            for (int width = firstWidthOffset; width <= lastWidthOffset; width++) {
                int x = center.getX();
                int z = center.getZ();
                if (direction.getAxis() == Direction.Axis.X) {
                    z += width;
                } else {
                    x += width;
                }

                // A path plan may be applied after its building is placed. Reject
                // every cell inside a reserved building box so a stale or malformed
                // route cannot overwrite doors, walls, or other structure blocks.
                if (roads.isInsideBuilding(x, z)) {
                    return List.of();
                }

                boolean fixedEntranceRow = routeIndex < fixedEntranceRows;
                SurfaceSample sample = fixedEntranceRow
                        ? fixedEntranceSurface(level, x, center.getY(), z)
                        : surfaceCache.get(x, z);
                if (sample == null
                        || (!fixedEntranceRow && !isUsablePathSurface(sample.state()))) {
                    return List.of();
                }

                // Preserve actual street blocks after the entrance prefix. A
                // street piece's bounding box also contains terrain, so skipping
                // the whole box would leave a grass gap before the real road.
                // Fixed entrance rows are intentionally retained in the plan:
                // bank placement grades and clears that apron after this plan is
                // created, so those road blocks must be restored afterward.
                if (!fixedEntranceRow && isStreetSurface(sample.state().getBlock())) {
                    continue;
                }

                cells.putIfAbsent(sample.pos(), new PlannedPathCell(sample.pos(), sample.snowPos()));
            }
        }
        return new ArrayList<>(cells.values());
    }

    @Nullable
    private SurfaceSample fixedEntranceSurface(ServerLevel level, int x, int y, int z) {
        BlockPos surfacePos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(surfacePos);
        if (!state.getFluidState().isEmpty()) {
            return null;
        }

        BlockPos snowPos = null;
        if (state.is(Blocks.SNOW)) {
            snowPos = surfacePos;
            surfacePos = surfacePos.below();
            state = level.getBlockState(surfacePos);
        } else {
            BlockPos above = surfacePos.above();
            if (level.getBlockState(above).is(Blocks.SNOW)) {
                snowPos = above;
            }
        }
        return new SurfaceSample(surfacePos, state, snowPos);
    }

    private void applyPlacementPlan(ServerLevel level, List<PlannedPathCell> cells,
                                    VillagePathBlocks pathBlocks) {
        BlockState surfaceState = pathBlocks.surfaceBlock().defaultBlockState();
        BlockState supportState = pathBlocks.supportBlock().defaultBlockState();

        for (PlannedPathCell cell : cells) {
            if (cell.snowPos() != null && level.getBlockState(cell.snowPos()).is(Blocks.SNOW)) {
                level.setBlock(cell.snowPos(), Blocks.AIR.defaultBlockState(), 2);
            }

            if (!level.getBlockState(cell.surfacePos()).is(pathBlocks.surfaceBlock())) {
                level.setBlock(cell.surfacePos(), surfaceState, 2);
            }

            BlockPos below = cell.surfacePos().below();
            BlockState belowState = level.getBlockState(below);
            if (belowState.isAir() || !belowState.getFluidState().isEmpty()) {
                level.setBlock(below, supportState, 2);
            }
        }
    }

    private boolean ensureSearchChunksLoaded(ServerLevel level, BlockPos start, BlockPos target,
                                             ChunkLoadBudget budget, int pathWidth) {
        int minX = Math.min(start.getX(), target.getX()) - SEARCH_MARGIN - pathWidth;
        int maxX = Math.max(start.getX(), target.getX()) + SEARCH_MARGIN + pathWidth;
        int minZ = Math.min(start.getZ(), target.getZ()) - SEARCH_MARGIN - pathWidth;
        int maxZ = Math.max(start.getZ(), target.getZ()) + SEARCH_MARGIN + pathWidth;

        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!budget.ensureLoaded(level, chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Direction routeDirection(List<BlockPos> route, int index, Direction fallback) {
        if (index + 1 < route.size()) {
            Direction direction = horizontalDirection(route.get(index), route.get(index + 1));
            if (direction != null) {
                return direction;
            }
        }
        if (index > 0) {
            Direction direction = horizontalDirection(route.get(index - 1), route.get(index));
            if (direction != null) {
                return direction;
            }
        }
        return fallback;
    }

    @Nullable
    private Direction horizontalDirection(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx > 0) return Direction.EAST;
        if (dx < 0) return Direction.WEST;
        if (dz > 0) return Direction.SOUTH;
        if (dz < 0) return Direction.NORTH;
        return null;
    }

    private boolean isUsablePathSurface(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        Block block = state.getBlock();
        return isStreetSurface(block)
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.PODZOL
                || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT
                || block == Blocks.MYCELIUM
                || block == Blocks.MUD
                || block == Blocks.CLAY
                || block == Blocks.SNOW_BLOCK
                || block == Blocks.STONE
                || block == Blocks.ANDESITE
                || block == Blocks.DIORITE
                || block == Blocks.GRANITE
                || block == Blocks.TUFF;
    }

    private boolean isStreetSurface(Block block) {
        return block == Blocks.DIRT_PATH
                || block == Blocks.GRAVEL
                || block == Blocks.SAND
                || block == Blocks.RED_SAND
                || block == Blocks.COBBLESTONE
                || block == Blocks.MOSSY_COBBLESTONE;
    }

    private int streetSurfacePenalty(Block block) {
        if (block == Blocks.DIRT_PATH || block == Blocks.SAND || block == Blocks.RED_SAND) {
            return 0;
        }
        if (block == Blocks.GRAVEL) {
            return 10;
        }
        return 30;
    }

    private boolean isPathPiece(StructurePiece piece) {
        if (!(piece instanceof PoolElementStructurePiece poolPiece)) {
            return false;
        }
        String element = poolPiece.getElement().toString().toLowerCase(Locale.ROOT);
        return element.contains("/streets/") || element.contains("/street/");
    }

    private int heuristic(int x, int z, BlockPos target) {
        return (Math.abs(target.getX() - x) + Math.abs(target.getZ() - z)) * 10;
    }

    private long horizontalDistanceSq(BlockPos from, int x, int z) {
        long dx = (long) x - from.getX();
        long dz = (long) z - from.getZ();
        return dx * dx + dz * dz;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static final class SurfaceCache {
        private final ServerLevel level;
        private final Map<Long, SurfaceSample> samples = new HashMap<>();
        private final Set<Long> invalidColumns = new HashSet<>();

        private SurfaceCache(ServerLevel level) {
            this.level = level;
        }

        @Nullable
        private SurfaceSample get(int x, int z) {
            long key = columnKey(x, z);
            SurfaceSample cached = samples.get(key);
            if (cached != null || invalidColumns.contains(key)) {
                return cached;
            }
            if (!level.hasChunk(x >> 4, z >> 4)) {
                invalidColumns.add(key);
                return null;
            }

            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            if (y < level.getMinBuildHeight()) {
                invalidColumns.add(key);
                return null;
            }

            BlockPos surfacePos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(surfacePos);
            BlockPos snowPos = null;
            if (state.is(Blocks.SNOW)) {
                snowPos = surfacePos;
                surfacePos = surfacePos.below();
                state = level.getBlockState(surfacePos);
            } else {
                BlockPos above = surfacePos.above();
                if (level.getBlockState(above).is(Blocks.SNOW)) {
                    snowPos = above;
                }
            }

            SurfaceSample sample = new SurfaceSample(surfacePos, state, snowPos);
            samples.put(key, sample);
            return sample;
        }

        private void clear() {
            samples.clear();
            invalidColumns.clear();
        }
    }

    private record SurfaceSample(BlockPos pos, BlockState state, @Nullable BlockPos snowPos) {
    }

    private record StreetTarget(BlockPos pos, Block surfaceBlock, long score) {
    }

    private record RouteKey(int x, int z, Direction incomingDirection) {
    }

    private record OpenRouteNode(RouteKey key, int estimatedTotalCost) {
    }

    private record PlannedPathCell(BlockPos surfacePos, @Nullable BlockPos snowPos) {
    }

    private record StreetCell(BlockPos pos) {
    }

    public static final class PreparedVillageRoads {
        private final Map<Long, StreetCell> streetByColumn;
        private final Map<Long, List<BoundingBox>> buildingsByChunk;

        private PreparedVillageRoads(Map<Long, StreetCell> streetByColumn,
                                     Map<Long, List<BoundingBox>> buildingsByChunk) {
            this.streetByColumn = streetByColumn;
            this.buildingsByChunk = buildingsByChunk;
        }

        private Map<Long, StreetCell> streetByColumn() {
            return streetByColumn;
        }

        private boolean isInsideBuilding(int x, int z) {
            List<BoundingBox> boxes = buildingsByChunk.get(columnKey(x >> 4, z >> 4));
            if (boxes == null) {
                return false;
            }
            for (BoundingBox box : boxes) {
                if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                    return true;
                }
            }
            return false;
        }

        /** Returns true when any cached village street surface lies in the footprint. */
        boolean intersectsStreet(BoundingBox footprint) {
            return intersectsStreet(footprint, streetByColumn.keySet());
        }

        static boolean intersectsStreet(BoundingBox footprint, Set<Long> streetColumns) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                    if (streetColumns.contains(columnKey(x, z))) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Adds pipeline reservations while retaining the one captured street snapshot. */
        public PreparedVillageRoads withAdditionalBuildings(List<BoundingBox> additionalBoxes) {
            if (additionalBoxes.isEmpty()) {
                return this;
            }
            Map<Long, List<BoundingBox>> merged = new HashMap<>();
            buildingsByChunk.forEach((key, boxes) -> merged.put(key, new ArrayList<>(boxes)));
            for (BoundingBox box : additionalBoxes) {
                for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
                    for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                        merged.computeIfAbsent(columnKey(chunkX, chunkZ), ignored -> new ArrayList<>())
                                .add(box);
                    }
                }
            }
            merged.replaceAll((ignored, boxes) -> List.copyOf(boxes));
            return new PreparedVillageRoads(streetByColumn, merged);
        }
    }

    /** Immutable path plan whose occupied surface columns can be reserved before placement. */
    public static final class PlannedPath {
        private final List<PlannedPathCell> cells;
        private final VillagePathBlocks pathBlocks;
        private final String connectionType;
        private final int pathWidth;
        private final BlockPos pathStart;
        private final BlockPos target;

        private PlannedPath(List<PlannedPathCell> cells, VillagePathBlocks pathBlocks,
                            String connectionType, int pathWidth,
                            BlockPos pathStart, BlockPos target) {
            this.cells = cells;
            this.pathBlocks = pathBlocks;
            this.connectionType = connectionType;
            this.pathWidth = pathWidth;
            this.pathStart = pathStart;
            this.target = target;
        }

        public List<BlockPos> reservedSurfaceCells() {
            return cells.stream().map(PlannedPathCell::surfacePos).toList();
        }
    }

    private record ConnectionProfile(String connectionType, int pathWidth,
                                     int fixedEntranceRows, int maxTargetDistance,
                                     int maxRouteSteps) {
    }
}
