package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.ChunkLoadBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
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
            new ConnectionProfile("Farm", 3, 0, 64, 96);
    private static final ConnectionProfile LIBRARY_PROFILE =
            new ConnectionProfile("Library", 3, 3, 96, 144);
    private static final ConnectionProfile LUMBERMILL_PROFILE =
            new ConnectionProfile("Lumbermill", 3, 0, 96, 144);
    private static final int MAX_VISITED_NODES = 20_000;
    private static final int SEARCH_MARGIN = 12;
    private static final int MAX_TARGET_ATTEMPTS = 24;
    /**
     * Server-thread time one path may spend trying street targets. Most late
     * attempts fail, so stop after this instead of running all 24 searches
     * in a single tick. The first target is always tried.
     */
    private static final long MAX_PLANNING_NANOS_PER_PATH = 8_000_000L;
    private static final long NO_DEADLINE = Long.MIN_VALUE;
    /**
     * Server-thread time the first route search of a connection may use, about
     * nine thousand nodes on a warm server. A search that reaches no street
     * ends here instead of running to the node cap; retries share the
     * connection's planning deadline.
     */
    private static final long MAX_SEARCH_NANOS = 40_000_000L;
    /** Route searches per connection; each retry drops a target whose placement failed. */
    private static final int MAX_ROUTE_SEARCHES = 4;
    private static final int MIN_TARGET_SPACING_SQ = 9;
    /** Highest climb one graded ramp makes; it rises one block per column. */
    private static final int MAX_RAMP_RISE = 4;
    /** Most blocks one ramp column may be cut or filled away from its terrain. */
    private static final int MAX_RAMP_EARTHWORK = 2;
    private static final int MAX_WATER_BRIDGE_SPAN = 10;
    private static final int MAX_GAP_BRIDGE_SPAN = 6;
    /** A dry column at least this far below the path is a gap to bridge, not a step. */
    private static final int MIN_GAP_DROP = 2;
    private static final int MAX_POST_DEPTH = 8;
    /** Most deck rows between two posts; interior posts must reach the ground. */
    private static final int MAX_POST_SPACING = 3;
    private static final int RAMP_COLUMN_COST = 6;
    private static final int EARTHWORK_BLOCK_COST = 4;
    private static final int BRIDGE_COST = 40;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final Comparator<StreetTarget> STREET_TARGET_ORDER =
            Comparator.comparingLong(StreetTarget::score)
                    .thenComparingInt(target -> target.pos().getX())
                    .thenComparingInt(target -> target.pos().getY())
                    .thenComparingInt(target -> target.pos().getZ());

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
                entranceDirection, BANK_PROFILE, preparedRoads, null, true, NO_DEADLINE));
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
                entranceDirection, FARM_PROFILE, null, null, true, NO_DEADLINE));
    }

    /** Reuses the stable village street and building snapshot across farm paths. */
    public void generateSurfaceConnection(ServerLevel level, BlockPos pathStart,
                                          BlockPos pathTargetHint,
                                          List<StructurePiece> villagePieces,
                                          String biomeType,
                                          Direction entranceDirection,
                                          PreparedVillageRoads preparedRoads) {
        place(level, planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, FARM_PROFILE, preparedRoads, null, true, NO_DEADLINE));
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
        return planBankConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, preparedRoads, sharedLoadBudget, true);
    }

    /**
     * Plans a bank connector; a caller with a fallback connector passes
     * {@code warnOnFailure = false} and reports only when no connector remains.
     */
    @Nullable
    public PlannedPath planBankConnection(ServerLevel level, BlockPos pathStart,
                                          BlockPos pathTargetHint,
                                          List<StructurePiece> villagePieces,
                                          String biomeType,
                                          Direction entranceDirection,
                                          PreparedVillageRoads preparedRoads,
                                          ChunkLoadBudget sharedLoadBudget,
                                          boolean warnOnFailure) {
        return planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, BANK_PROFILE, preparedRoads, sharedLoadBudget, warnOnFailure,
                NO_DEADLINE);
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
        return planFarmConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, preparedRoads, sharedLoadBudget, true, newPlanningDeadline());
    }

    /**
     * Plans a farm connector; a caller trying several farm edges passes
     * {@code warnOnFailure = false} for every edge except its last and shares
     * one {@link #newPlanningDeadline()} across all of them.
     */
    @Nullable
    public PlannedPath planFarmConnection(ServerLevel level, BlockPos pathStart,
                                          BlockPos pathTargetHint,
                                          List<StructurePiece> villagePieces,
                                          String biomeType,
                                          Direction entranceDirection,
                                          PreparedVillageRoads preparedRoads,
                                          ChunkLoadBudget sharedLoadBudget,
                                          boolean warnOnFailure,
                                          long planningDeadline) {
        return planConnection(level, pathStart, pathTargetHint, villagePieces, biomeType,
                entranceDirection, FARM_PROFILE, preparedRoads, sharedLoadBudget, warnOnFailure,
                planningDeadline);
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
                entranceDirection, LIBRARY_PROFILE, preparedRoads, sharedLoadBudget, true, NO_DEADLINE);
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
                entranceDirection, LUMBERMILL_PROFILE, preparedRoads, sharedLoadBudget, true, NO_DEADLINE);
    }

    /** Deadline for one connection's planning, shared by callers that try several entrances. */
    public static long newPlanningDeadline() {
        return System.nanoTime() + MAX_PLANNING_NANOS_PER_PATH;
    }

    /** Applies a previously reserved plan after every village building is in place. */
    public void place(ServerLevel level, @Nullable PlannedPath path) {
        if (path == null) {
            return;
        }
        applyPlacementPlan(level, path);
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP] Generated {}-wide {} path with {} surface blocks from {} "
                        + "to village street at {} using {}",
                path.pathWidth, path.connectionType.toLowerCase(Locale.ROOT),
                path.cells.size(), path.pathStart, path.target, path.pathBlocks.surfaceBlock());
    }

    /**
     * Captures the street surface columns of one explicit area, with no
     * building obstacles. GameTest fixtures use this because they have no
     * village structure pieces.
     */
    public PreparedVillageRoads prepareStreetArea(ServerLevel level, BoundingBox streetArea) {
        SurfaceCache surfaceCache = new SurfaceCache(level);
        Map<Long, StreetCell> streetByColumn = new HashMap<>();
        for (int x = streetArea.minX(); x <= streetArea.maxX(); x++) {
            for (int z = streetArea.minZ(); z <= streetArea.maxZ(); z++) {
                SurfaceSample sample = surfaceCache.get(x, z);
                if (sample != null && isStreetSurface(sample.state().getBlock())) {
                    streetByColumn.put(columnKey(x, z), new StreetCell(sample.pos()));
                }
            }
        }
        return new PreparedVillageRoads(streetByColumn, Map.of(), null);
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
        return new PreparedVillageRoads(streetByColumn, buildingsByChunk, null);
    }

    /** A non-final attempt logs at debug; its caller reports only if no connector remains. */
    private static void logPlanningFailure(boolean warnOnFailure, String message, Object... args) {
        if (warnOnFailure) {
            EmeraldCapitalism.LOGGER.warn(message, args);
        } else {
            EmeraldCapitalism.LOGGER.debug(message, args);
        }
    }

    @Nullable
    private PlannedPath planConnection(ServerLevel level, BlockPos pathStart,
                                       BlockPos pathTargetHint,
                                       List<StructurePiece> villagePieces,
                                       String biomeType,
                                       Direction entranceDirection,
                                       ConnectionProfile profile,
                                       @Nullable PreparedVillageRoads preparedRoads,
                                       @Nullable ChunkLoadBudget sharedLoadBudget,
                                       boolean warnOnFailure,
                                       long planningDeadline) {
        if (!entranceDirection.getAxis().isHorizontal()) {
            return null;
        }

        PreparedVillageRoads roads = preparedRoads != null
                ? preparedRoads
                : prepare(level, villagePieces);
        SurfaceCache surfaceCache = new SurfaceCache(level);
        StreetTargetSearch targetSearch = findStreetTargets(
                level, pathStart, pathTargetHint, roads.streetByColumn(),
                profile.maxTargetDistance(), profile.maxRouteSteps() + profile.fixedEntranceRows());
        List<StreetTarget> streetTargets = targetSearch.targets();
        if (streetTargets.isEmpty()) {
            logPlanningFailure(warnOnFailure,
                    "[ECAP] {} path at {} found no actual street surface near target hint {}",
                    profile.connectionType(), pathStart, pathTargetHint);
            return null;
        }

        List<BlockPos> entrancePrefix = profile.fixedEntranceRows() > 0
                ? buildEntrancePrefix(level, pathStart, entranceDirection, profile.fixedEntranceRows())
                : buildSurfaceEntrance(level, pathStart);
        if (entrancePrefix.isEmpty()) {
            logPlanningFailure(warnOnFailure,
                    "[ECAP] {} path entrance chunk is unavailable at {}",
                    profile.connectionType(), pathStart);
            return null;
        }

        BlockPos searchStart = entrancePrefix.get(entrancePrefix.size() - 1);
        int targetAttempts = Math.min(MAX_TARGET_ATTEMPTS, streetTargets.size());
        long planningStarted = System.nanoTime();
        long deadline = planningDeadline == NO_DEADLINE
                ? planningStarted + MAX_PLANNING_NANOS_PER_PATH : planningDeadline;
        // Per-target failure reasons are collected only when performance timing
        // is enabled, so the normal generation path does no extra allocation.
        StringBuilder diagnostics = PerformanceTimingCounters.isDetailedTimingEnabled()
                ? new StringBuilder() : null;
        PlacementRejection placementRejection = new PlacementRejection();

        List<StreetTarget> candidates = new ArrayList<>(targetAttempts);
        for (int targetIndex = 0; targetIndex < targetAttempts; targetIndex++) {
            StreetTarget streetTarget = streetTargets.get(targetIndex);
            if (manhattan(searchStart, streetTarget.pos()) > profile.maxRouteSteps()) {
                appendDiagnostic(diagnostics, targetIndex, streetTarget, "BEYOND_STEP_BUDGET");
                continue;
            }
            // Never load chunks here: a blocking load during route search stalls
            // the server tick. Every world read in the search and placement plan
            // checks hasChunk first, so unloaded columns are simply impassable;
            // only the two endpoints must be loaded.
            if (!endpointChunksLoaded(level, searchStart, streetTarget.pos())) {
                appendDiagnostic(diagnostics, targetIndex, streetTarget, "CHUNK_UNAVAILABLE");
                continue;
            }
            candidates.add(streetTarget);
        }

        // One search heads for every candidate street at once and stops at the
        // first one it reaches, so an unreachable nearby street cannot use up
        // the planning time before a reachable one is tried.
        surfaceCache.forgetUnavailableColumns();
        for (int searchIndex = 0; searchIndex < MAX_ROUTE_SEARCHES && !candidates.isEmpty(); searchIndex++) {
            long searchStarted = System.nanoTime();
            if (searchIndex > 0 && searchStarted > deadline) {
                appendDiagnostic(diagnostics, searchIndex, null, "TIME_LIMIT after "
                        + (searchStarted - planningStarted) / 1_000_000L + "ms, "
                        + candidates.size() + " targets left");
                break;
            }
            RouteSearch search = findRoute(level, searchStart, candidates, entranceDirection,
                    roads, surfaceCache, profile.pathWidth(), profile.maxRouteSteps(),
                    searchIndex == 0 ? Math.max(deadline, searchStarted + MAX_SEARCH_NANOS) : deadline);
            List<RouteCell> route = search.route();
            StreetTarget streetTarget = search.target();
            if (route.isEmpty() || streetTarget == null) {
                if (diagnostics != null) {
                    appendDiagnostic(diagnostics, searchIndex, null, "ROUTE_" + search.outcome()
                            + " visited=" + search.visited()
                            + " targets=" + candidates.size()
                            + " ms=" + (System.nanoTime() - searchStarted) / 1_000_000L
                            + (search.visited() == 1 ? " firstStep{" + search.firstStepRejections() + "}" : ""));
                }
                break;
            }

            List<RouteCell> completeRoute = new ArrayList<>(entrancePrefix.size() + route.size());
            for (BlockPos prefixCell : entrancePrefix) {
                completeRoute.add(new RouteCell(prefixCell, CellKind.PATH));
            }
            completeRoute.addAll(route.subList(1, route.size()));
            VillagePathBlocks pathBlocks = VillagePathBlocks.matchingStreet(
                    streetTarget.surfaceBlock(), biomeType);
            PlacementPlan placementPlan = createPlacementPlan(
                    level, completeRoute, search.features(), profile.fixedEntranceRows(),
                    entranceDirection, roads, surfaceCache, profile.pathWidth(), pathBlocks,
                    placementRejection);
            if (placementPlan == null) {
                appendDiagnostic(diagnostics, searchIndex, streetTarget,
                        "PLACEMENT_" + placementRejection.reason + " at " + placementRejection.column);
                candidates.remove(streetTarget);
                continue;
            }

            if (diagnostics != null) {
                EmeraldCapitalism.LOGGER.info(
                        "[ECAP][RoadDiag] {} path from {} planned in {} ms after {} search(es)",
                        profile.connectionType().toLowerCase(Locale.ROOT), pathStart,
                        (System.nanoTime() - planningStarted) / 1_000_000L, searchIndex + 1);
            }
            return new PlannedPath(List.copyOf(placementPlan.cells()),
                    List.copyOf(placementPlan.decorations()), pathBlocks,
                    roads.bridgeBlocks(biomeType), profile.connectionType(), profile.pathWidth(),
                    pathStart, streetTarget.pos());
        }

        if (warnOnFailure) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Could not plan a complete {} path from {} to a reachable village street",
                    profile.connectionType().toLowerCase(Locale.ROOT), pathStart);
        } else {
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP] {} path attempt from {} found no street; the caller will retry or fall back",
                    profile.connectionType().toLowerCase(Locale.ROOT), pathStart);
        }
        if (diagnostics != null) {
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP][RoadDiag] {} path from {} failed in {} ms (search start {}, facing {}, hint {}): "
                            + "streetColumns={} inRange={} largestNetwork={} targets={} attempts:{}",
                    profile.connectionType().toLowerCase(Locale.ROOT), pathStart,
                    (System.nanoTime() - planningStarted) / 1_000_000L, searchStart,
                    entranceDirection, pathTargetHint, roads.streetByColumn().size(),
                    targetSearch.inRangeCount(), targetSearch.largestNetworkSize(),
                    streetTargets.size(), diagnostics);
        }
        return null;
    }

    private static void appendDiagnostic(@Nullable StringBuilder diagnostics, int targetIndex,
                                         @Nullable StreetTarget target, String reason) {
        if (diagnostics == null) {
            return;
        }
        diagnostics.append(" [#").append(targetIndex);
        if (target != null) {
            diagnostics.append(' ').append(target.pos().getX()).append(',')
                    .append(target.pos().getY()).append(',').append(target.pos().getZ());
        }
        diagnostics.append(' ').append(reason).append(']');
    }

    private static int manhattan(BlockPos from, BlockPos to) {
        return Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ());
    }

    private StreetTargetSearch findStreetTargets(ServerLevel level, BlockPos from,
                                                  BlockPos targetHint,
                                                  Map<Long, StreetCell> streetByColumn,
                                                  int maxTargetDistance,
                                                  int maxManhattanDistance) {
        Map<Long, StreetTarget> candidatesByColumn = new HashMap<>();
        long maxDistanceSq = (long) maxTargetDistance * maxTargetDistance;

        for (Map.Entry<Long, StreetCell> entry : streetByColumn.entrySet()) {
            StreetCell cell = entry.getValue();
            if (!level.hasChunk(cell.pos().getX() >> 4, cell.pos().getZ() >> 4)) {
                continue;
            }
            Block currentSurface = level.getBlockState(cell.pos()).getBlock();
            if (!isStreetSurface(currentSurface)) {
                continue;
            }
            long distanceSq = horizontalDistanceSq(from, cell.pos().getX(), cell.pos().getZ());
            if (distanceSq > maxDistanceSq) {
                continue;
            }
            // A route moves one column per step, so a street cell farther than the
            // step budget in Manhattan distance can never be reached.
            if (Math.abs(cell.pos().getX() - from.getX())
                    + Math.abs(cell.pos().getZ() - from.getZ()) > maxManhattanDistance) {
                continue;
            }
            // Building-specific orientation chooses the intended street edge. Favor
            // that hint first, then use entrance distance as the deterministic tie-breaker.
            long hintDistanceSq = horizontalDistanceSq(targetHint, cell.pos().getX(), cell.pos().getZ());
            long score = hintDistanceSq * 100L + distanceSq + streetSurfacePenalty(currentSurface);
            candidatesByColumn.put(entry.getKey(),
                    new StreetTarget(cell.pos(), currentSurface, score));
        }

        Set<Long> mainNetwork = largestConnectedStreetNetwork(candidatesByColumn);
        List<StreetTarget> candidates = mainNetwork.stream()
                .map(candidatesByColumn::get)
                .sorted(STREET_TARGET_ORDER)
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
        return new StreetTargetSearch(targets, candidatesByColumn.size(), mainNetwork.size());
    }

    private Set<Long> largestConnectedStreetNetwork(Map<Long, StreetTarget> streetByColumn) {
        Set<Long> unvisited = new HashSet<>(streetByColumn.keySet());
        Set<Long> largest = Set.of();
        long largestNearestScore = Long.MAX_VALUE;
        long largestAnchor = Long.MAX_VALUE;

        while (!unvisited.isEmpty()) {
            long start = unvisited.stream().min(Long::compare).orElseThrow();
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

            long componentAnchor = component.stream().min(Long::compare).orElseThrow();
            if (component.size() > largest.size()
                    || (component.size() == largest.size() && nearestScore < largestNearestScore)
                    || (component.size() == largest.size() && nearestScore == largestNearestScore
                    && componentAnchor < largestAnchor)) {
                largest = component;
                largestNearestScore = nearestScore;
                largestAnchor = componentAnchor;
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

    /**
     * A* from {@code start} to whichever target street column it reaches first.
     * The heuristic is the Manhattan distance to the nearest target, which stays
     * admissible because every step costs at least ten per column.
     */
    private RouteSearch findRoute(ServerLevel level, BlockPos start, List<StreetTarget> targets,
                                  Direction startDirection, PreparedVillageRoads roads,
                                  SurfaceCache surfaceCache, int pathWidth,
                                  int maxRouteSteps, long searchDeadline) {
        Map<Long, StreetTarget> targetsByColumn = new HashMap<>();
        int[] targetXs = new int[targets.size()];
        int[] targetZs = new int[targets.size()];
        int minX = start.getX();
        int maxX = start.getX();
        int minZ = start.getZ();
        int maxZ = start.getZ();
        for (int index = 0; index < targets.size(); index++) {
            BlockPos target = targets.get(index).pos();
            targetsByColumn.putIfAbsent(columnKey(target.getX(), target.getZ()), targets.get(index));
            targetXs[index] = target.getX();
            targetZs[index] = target.getZ();
            minX = Math.min(minX, target.getX());
            maxX = Math.max(maxX, target.getX());
            minZ = Math.min(minZ, target.getZ());
            maxZ = Math.max(maxZ, target.getZ());
        }
        minX -= SEARCH_MARGIN;
        maxX += SEARCH_MARGIN;
        minZ -= SEARCH_MARGIN;
        maxZ += SEARCH_MARGIN;

        RouteKey startKey = new RouteKey(start.getX(), start.getZ(), startDirection);
        PriorityQueue<OpenRouteNode> open = new PriorityQueue<>(
                Comparator.comparingInt(OpenRouteNode::estimatedTotalCost)
                        .thenComparingInt(node -> node.key().x())
                        .thenComparingInt(node -> node.key().z())
                        .thenComparingInt(node -> directionOrder(node.key().incomingDirection())));
        Map<RouteKey, Integer> costs = new HashMap<>();
        Map<RouteKey, Integer> steps = new HashMap<>();
        Map<RouteKey, RouteKey> cameFrom = new HashMap<>();
        Map<RouteKey, RouteFeature> features = new HashMap<>();
        // A ramp or bridge depends only on the column it leaves and its direction.
        Map<RouteKey, List<FeatureMove>> featureMoveCache = new HashMap<>();
        SearchBounds bounds = new SearchBounds(minX, maxX, minZ, maxZ);
        Set<RouteKey> closed = new HashSet<>();

        costs.put(startKey, 0);
        steps.put(startKey, 0);
        open.add(new OpenRouteNode(startKey,
                nearestTargetDistance(startKey.x(), startKey.z(), targetXs, targetZs) * 10));

        int visited = 0;
        boolean stepLimited = false;
        boolean startSampled = false;
        StringBuilder firstStepRejections = PerformanceTimingCounters.isDetailedTimingEnabled()
                ? new StringBuilder() : null;
        boolean timeLimited = false;
        while (!open.isEmpty() && visited < MAX_VISITED_NODES) {
            OpenRouteNode queued = open.poll();
            RouteKey current = queued.key();
            if (!closed.add(current)) {
                continue;
            }
            visited++;
            if ((visited & 255) == 0 && System.nanoTime() > searchDeadline) {
                timeLimited = true;
                break;
            }

            StreetTarget reached = targetsByColumn.get(columnKey(current.x(), current.z()));
            if (reached != null) {
                return reconstructRoute(current, cameFrom, features, surfaceCache, visited, reached);
            }

            int currentSteps = steps.getOrDefault(current, maxRouteSteps);
            if (currentSteps >= maxRouteSteps) {
                stepLimited = true;
                continue;
            }

            SurfaceSample currentSurface = surfaceCache.get(current.x(), current.z());
            if (currentSurface == null) {
                continue;
            }
            startSampled = true;

            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                if (direction == current.incomingDirection().getOpposite()) {
                    continue;
                }

                int nextX = current.x() + direction.getStepX();
                int nextZ = current.z() + direction.getStepZ();
                if (nextX < minX || nextX > maxX || nextZ < minZ || nextZ > maxZ) {
                    continue;
                }
                // Every step moves one column, so a node that cannot reach the
                // target within the remaining step budget can never succeed.
                if (currentSteps + 1 + nearestTargetDistance(nextX, nextZ, targetXs, targetZs)
                        > maxRouteSteps) {
                    stepLimited = true;
                    continue;
                }
                // Placement widens each route cell across its outgoing direction,
                // so a turn also sweeps the current cell's row on the new axis.
                if (direction.getAxis() != current.incomingDirection().getAxis()
                        && !isTurnRowClear(level, currentSurface, direction, roads, surfaceCache,
                        pathWidth)) {
                    continue;
                }

                SurfaceSample nextSurface = surfaceCache.get(nextX, nextZ);
                if (nextSurface == null || !isCorridorRowTraversable(level, nextSurface,
                        currentSurface, direction, roads, surfaceCache, pathWidth)) {
                    if (visited == 1 && firstStepRejections != null) {
                        firstStepRejections.append(direction.getSerializedName().charAt(0)).append('=')
                                .append(describeRowRejection(level, nextSurface, currentSurface,
                                        direction, roads, surfaceCache, pathWidth))
                                .append(' ');
                    }
                    if (nextSurface == null) {
                        continue;
                    }
                    List<FeatureMove> moves = featureMoveCache.computeIfAbsent(
                            new RouteKey(current.x(), current.z(), direction),
                            ignored -> featureMoves(level, currentSurface, nextSurface, direction,
                                    roads, surfaceCache, pathWidth, bounds));
                    for (FeatureMove move : moves) {
                        RouteKey landing = new RouteKey(move.landing().pos().getX(),
                                move.landing().pos().getZ(), direction);
                        int landingSteps = currentSteps + move.columns();
                        if (closed.contains(landing)) {
                            continue;
                        }
                        if (landingSteps + nearestTargetDistance(landing.x(), landing.z(),
                                targetXs, targetZs) > maxRouteSteps) {
                            stepLimited = true;
                            continue;
                        }
                        int turnCost = direction == current.incomingDirection() ? 0 : 3;
                        int landingCost = costs.get(current) + move.cost() + turnCost;
                        if (landingCost >= costs.getOrDefault(landing, Integer.MAX_VALUE)) {
                            continue;
                        }
                        costs.put(landing, landingCost);
                        steps.put(landing, landingSteps);
                        cameFrom.put(landing, current);
                        features.put(landing, move.feature());
                        open.add(new OpenRouteNode(landing,
                                landingCost + nearestTargetDistance(landing.x(), landing.z(),
                                        targetXs, targetZs) * 10));
                    }
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
                features.remove(next);
                open.add(new OpenRouteNode(next,
                        nextCost + nearestTargetDistance(nextX, nextZ, targetXs, targetZs) * 10));
            }
        }

        RouteOutcome outcome;
        if (timeLimited) {
            outcome = RouteOutcome.TIME_LIMIT;
        } else if (!startSampled) {
            outcome = RouteOutcome.START_UNAVAILABLE;
        } else if (visited >= MAX_VISITED_NODES) {
            outcome = RouteOutcome.VISITED_CAP;
        } else if (stepLimited) {
            outcome = RouteOutcome.EXHAUSTED_WITH_STEP_PRUNING;
        } else {
            outcome = RouteOutcome.BLOCKED;
        }
        return new RouteSearch(List.of(), List.of(), null, outcome, visited,
                firstStepRejections == null ? "" : firstStepRejections.toString().trim());
    }

    /** Diagnostic-only description of why one corridor row cannot be entered. */
    private String describeRowRejection(ServerLevel level, @Nullable SurfaceSample center,
                                        SurfaceSample previousCenter, Direction travelDirection,
                                        PreparedVillageRoads roads, SurfaceCache surfaceCache,
                                        int pathWidth) {
        if (center == null) {
            return "no_surface";
        }
        if (Math.abs(center.pos().getY() - previousCenter.pos().getY()) > 1) {
            return "step(" + previousCenter.pos().getY() + "->" + center.pos().getY() + ")";
        }
        int firstWidthOffset = -(pathWidth / 2);
        for (int width = firstWidthOffset; width < firstWidthOffset + pathWidth; width++) {
            int x = center.pos().getX();
            int z = center.pos().getZ();
            if (travelDirection.getAxis() == Direction.Axis.X) {
                z += width;
            } else {
                x += width;
            }
            if (!level.hasChunk(x >> 4, z >> 4)) {
                return "unloaded@" + x + "," + z;
            }
            if (roads.isInsideBuilding(x, z)) {
                return "building" + roads.buildingAt(x, z) + "@" + x + "," + z;
            }
            SurfaceSample sample = surfaceCache.get(x, z);
            if (sample == null) {
                return "no_side_surface@" + x + "," + z;
            }
            if (Math.abs(sample.pos().getY() - center.pos().getY()) > 1) {
                return "side_step(" + center.pos().getY() + "->" + sample.pos().getY() + ")@" + x + "," + z;
            }
            if (!isUsablePathSurface(sample.state())) {
                return "surface(" + sample.state().getBlock().getDescriptionId() + ")@" + x + "," + z;
            }
        }
        return "unknown";
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

    /**
     * Placement widens each route cell across its outgoing direction, so a turn
     * also paves the current cell's row on the new axis. That row needs the same
     * surface and height checks as a row entered by a straight step.
     */
    private boolean isTurnRowClear(ServerLevel level, SurfaceSample center, Direction newDirection,
                                   PreparedVillageRoads roads, SurfaceCache surfaceCache,
                                   int pathWidth) {
        int x = center.pos().getX();
        int z = center.pos().getZ();
        int firstWidthOffset = -(pathWidth / 2);
        for (int width = firstWidthOffset; width < firstWidthOffset + pathWidth; width++) {
            int rowX = newDirection.getAxis() == Direction.Axis.X ? x : x + width;
            int rowZ = newDirection.getAxis() == Direction.Axis.X ? z + width : z;
            if (!level.hasChunk(rowX >> 4, rowZ >> 4) || roads.isInsideBuilding(rowX, rowZ)) {
                return false;
            }
            SurfaceSample sample = surfaceCache.get(rowX, rowZ);
            if (sample == null || Math.abs(sample.pos().getY() - center.pos().getY()) > 1
                    || !isUsablePathSurface(sample.state())) {
                return false;
            }
        }
        return true;
    }

    /** Rebuilds the route, expanding each ramp or bridge into its own columns. */
    private RouteSearch reconstructRoute(RouteKey end, Map<RouteKey, RouteKey> cameFrom,
                                         Map<RouteKey, RouteFeature> features,
                                         SurfaceCache surfaceCache, int visited,
                                         StreetTarget target) {
        List<RouteCell> reversed = new ArrayList<>();
        List<RouteFeature> usedFeatures = new ArrayList<>();
        RouteKey current = end;
        while (current != null) {
            SurfaceSample sample = surfaceCache.get(current.x(), current.z());
            if (sample == null) {
                return new RouteSearch(List.of(), List.of(), null, RouteOutcome.RECONSTRUCT_FAILED,
                        visited, "");
            }
            reversed.add(new RouteCell(sample.pos(), CellKind.PATH));
            RouteFeature feature = features.get(current);
            if (feature != null) {
                usedFeatures.add(feature);
                for (int index = feature.centers().size() - 1; index >= 0; index--) {
                    reversed.add(new RouteCell(feature.centers().get(index), feature.kind()));
                }
            }
            current = cameFrom.get(current);
        }
        Collections.reverse(reversed);
        Collections.reverse(usedFeatures);
        return new RouteSearch(reversed, usedFeatures, target, RouteOutcome.FOUND, visited, "");
    }

    /**
     * Ramp and bridge moves leaving {@code from} in {@code direction}. They are
     * only considered where the plain one-column step is not traversable.
     */
    private List<FeatureMove> featureMoves(ServerLevel level, SurfaceSample from, SurfaceSample first,
                                           Direction direction, PreparedVillageRoads roads,
                                           SurfaceCache surfaceCache, int pathWidth,
                                           SearchBounds bounds) {
        if (!from.state().getFluidState().isEmpty()) {
            return List.of();
        }
        FeatureMove ramp = planRamp(level, from, first, direction, roads, surfaceCache, pathWidth, bounds);
        FeatureMove bridge = planBridge(level, from, first, direction, roads, surfaceCache, pathWidth, bounds);
        if (ramp == null) {
            return bridge == null ? List.of() : List.of(bridge);
        }
        return bridge == null ? List.of(ramp) : List.of(ramp, bridge);
    }

    /**
     * Grades a 2-to-4 block terrain step into a ramp rising one block per
     * column. Each ramp column is cut or filled by at most
     * {@link #MAX_RAMP_EARTHWORK}; placement then puts stairs on the ramp.
     */
    @Nullable
    private FeatureMove planRamp(ServerLevel level, SurfaceSample from, SurfaceSample first,
                                 Direction direction, PreparedVillageRoads roads,
                                 SurfaceCache surfaceCache, int pathWidth, SearchBounds bounds) {
        int startY = from.pos().getY();
        int rise = first.pos().getY() - startY;
        if (Math.abs(rise) < 2 || !first.state().getFluidState().isEmpty()) {
            return null;
        }
        int sign = Integer.signum(rise);
        int firstWidthOffset = -(pathWidth / 2);
        List<BlockPos> centers = new ArrayList<>();
        List<PlannedPathCell> cells = new ArrayList<>();
        int earthwork = 0;
        for (int step = 1; step <= MAX_RAMP_RISE + 1; step++) {
            int x = from.pos().getX() + direction.getStepX() * step;
            int z = from.pos().getZ() + direction.getStepZ() * step;
            if (!bounds.contains(x, z)) {
                return null;
            }
            SurfaceSample sample = surfaceCache.get(x, z);
            if (sample == null) {
                return null;
            }
            int previousY = startY + sign * (step - 1);
            if (step >= 2 && Math.abs(sample.pos().getY() - previousY) <= 1
                    && isCorridorRowTraversable(level, sample, null, direction, roads,
                    surfaceCache, pathWidth)) {
                int cost = step * (10 + RAMP_COLUMN_COST) + earthwork * EARTHWORK_BLOCK_COST
                        + Math.abs(sample.pos().getY() - startY) * 5;
                return new FeatureMove(new RouteFeature(CellKind.GRADED, direction,
                        List.copyOf(centers), List.copyOf(cells), List.of()), sample, step, cost);
            }
            if (step > MAX_RAMP_RISE) {
                return null;
            }
            int targetY = startY + sign * step;
            for (int offset = firstWidthOffset; offset < firstWidthOffset + pathWidth; offset++) {
                int columnX = rowX(x, direction, offset);
                int columnZ = rowZ(z, direction, offset);
                SurfaceSample column = gradableColumn(level, columnX, columnZ, targetY, roads, surfaceCache);
                if (column == null) {
                    return null;
                }
                earthwork += Math.abs(column.pos().getY() - targetY);
                cells.add(new PlannedPathCell(new BlockPos(columnX, targetY, columnZ), column.snowPos(),
                        CellKind.GRADED, column.pos().getY()));
            }
            centers.add(new BlockPos(x, targetY, z));
        }
        return null;
    }

    @Nullable
    private SurfaceSample gradableColumn(ServerLevel level, int x, int z, int targetY,
                                         PreparedVillageRoads roads, SurfaceCache surfaceCache) {
        if (!level.hasChunk(x >> 4, z >> 4) || roads.isInsideBuilding(x, z)
                || roads.streetByColumn().containsKey(columnKey(x, z))) {
            return null;
        }
        SurfaceSample sample = surfaceCache.get(x, z);
        if (sample == null || !isUsablePathSurface(sample.state()) || isConnectorSurface(sample.state())
                || Math.abs(sample.pos().getY() - targetY) > MAX_RAMP_EARTHWORK) {
            return null;
        }
        // A cut must not open the ramp's side walls onto water.
        for (int y = targetY + 1; y <= sample.pos().getY(); y++) {
            for (Direction side : HORIZONTAL_DIRECTIONS) {
                int sideX = x + side.getStepX();
                int sideZ = z + side.getStepZ();
                if (!level.hasChunk(sideX >> 4, sideZ >> 4)
                        || !level.getBlockState(new BlockPos(sideX, y, sideZ)).getFluidState().isEmpty()) {
                    return null;
                }
            }
        }
        return sample;
    }

    /**
     * Spans water up to {@link #MAX_WATER_BRIDGE_SPAN} columns, or a dry gap up
     * to {@link #MAX_GAP_BRIDGE_SPAN}, with a deck level with the higher bank.
     */
    @Nullable
    private FeatureMove planBridge(ServerLevel level, SurfaceSample from, SurfaceSample first,
                                   Direction direction, PreparedVillageRoads roads,
                                   SurfaceCache surfaceCache, int pathWidth, SearchBounds bounds) {
        int startY = from.pos().getY();
        boolean water = isWater(first.state());
        if (!water && !isDryGap(first, startY)) {
            return null;
        }
        int maxSpan = water ? MAX_WATER_BRIDGE_SPAN : MAX_GAP_BRIDGE_SPAN;
        for (int step = 1; step <= maxSpan + 1; step++) {
            int x = from.pos().getX() + direction.getStepX() * step;
            int z = from.pos().getZ() + direction.getStepZ() * step;
            if (!bounds.contains(x, z)) {
                return null;
            }
            SurfaceSample sample = surfaceCache.get(x, z);
            if (sample == null) {
                return null;
            }
            if (step >= 2 && sample.state().getFluidState().isEmpty()
                    && Math.abs(sample.pos().getY() - startY) <= 1
                    && isCorridorRowTraversable(level, sample, null, direction, roads,
                    surfaceCache, pathWidth)) {
                int deckY = Math.max(startY, sample.pos().getY());
                RouteFeature feature = bridgeFeature(level, from.pos(), direction, step - 1, deckY,
                        roads, surfaceCache, pathWidth);
                if (feature == null) {
                    return null;
                }
                int cost = step * 10 + BRIDGE_COST + Math.abs(sample.pos().getY() - startY) * 5;
                return new FeatureMove(feature, sample, step, cost);
            }
            if (step > maxSpan || !(isWater(sample.state()) || isDryGap(sample, startY))) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private RouteFeature bridgeFeature(ServerLevel level, BlockPos start, Direction direction,
                                       int span, int deckY, PreparedVillageRoads roads,
                                       SurfaceCache surfaceCache, int pathWidth) {
        int firstWidthOffset = -(pathWidth / 2);
        int lastWidthOffset = firstWidthOffset + pathWidth - 1;
        int[] railOffsets = {firstWidthOffset - 1, lastWidthOffset + 1};
        List<BlockPos> centers = new ArrayList<>();
        List<PlannedPathCell> cells = new ArrayList<>();
        List<PlannedDecoration> decorations = new ArrayList<>();
        Set<Long> deckColumns = new HashSet<>();
        for (int row = 1; row <= span; row++) {
            int x = start.getX() + direction.getStepX() * row;
            int z = start.getZ() + direction.getStepZ() * row;
            for (int offset = firstWidthOffset; offset <= lastWidthOffset; offset++) {
                int columnX = rowX(x, direction, offset);
                int columnZ = rowZ(z, direction, offset);
                SurfaceSample column = deckColumn(level, columnX, columnZ, deckY, roads, surfaceCache);
                if (column == null) {
                    return null;
                }
                cells.add(new PlannedPathCell(new BlockPos(columnX, deckY, columnZ), column.snowPos(),
                        CellKind.DECK, column.pos().getY()));
                deckColumns.add(columnKey(columnX, columnZ));
            }
            // Railings stand on one extra deck column at each side, where it is free.
            for (int offset : railOffsets) {
                int columnX = rowX(x, direction, offset);
                int columnZ = rowZ(z, direction, offset);
                SurfaceSample column = deckColumn(level, columnX, columnZ, deckY, roads, surfaceCache);
                if (column == null) {
                    continue;
                }
                cells.add(new PlannedPathCell(new BlockPos(columnX, deckY, columnZ), column.snowPos(),
                        CellKind.DECK, column.pos().getY()));
                deckColumns.add(columnKey(columnX, columnZ));
                decorations.add(new PlannedDecoration(new BlockPos(columnX, deckY + 1, columnZ),
                        DecorationKind.RAIL, direction));
            }
            centers.add(new BlockPos(x, deckY, z));
        }

        // End posts stand at the banks; interior posts split the span evenly so
        // no more than MAX_POST_SPACING rows are unsupported.
        int intervals = Math.max(1, (span - 1 + MAX_POST_SPACING - 1) / MAX_POST_SPACING);
        Set<Integer> interiorRows = new HashSet<>();
        for (int index = 1; index < intervals; index++) {
            interiorRows.add(1 + (int) Math.round((double) index * (span - 1) / intervals));
        }
        Set<Integer> postRows = new java.util.TreeSet<>(interiorRows);
        postRows.add(1);
        postRows.add(span);
        for (int row : postRows) {
            int x = start.getX() + direction.getStepX() * row;
            int z = start.getZ() + direction.getStepZ() * row;
            for (int side = 0; side < 2; side++) {
                int railOffset = railOffsets[side];
                int offset = deckColumns.contains(columnKey(rowX(x, direction, railOffset),
                        rowZ(z, direction, railOffset)))
                        ? railOffset : (side == 0 ? firstWidthOffset : lastWidthOffset);
                int columnX = rowX(x, direction, offset);
                int columnZ = rowZ(z, direction, offset);
                List<BlockPos> post = postColumn(level, columnX, deckY - 1, columnZ);
                if (post == null) {
                    // End posts are decorative; a long span needs its interior support.
                    if (interiorRows.contains(row)) {
                        return null;
                    }
                    continue;
                }
                for (BlockPos postPos : post) {
                    decorations.add(new PlannedDecoration(postPos, DecorationKind.POST, direction));
                }
            }
        }
        return new RouteFeature(CellKind.DECK, direction, List.copyOf(centers), List.copyOf(cells),
                List.copyOf(decorations));
    }

    @Nullable
    private SurfaceSample deckColumn(ServerLevel level, int x, int z, int deckY,
                                     PreparedVillageRoads roads, SurfaceCache surfaceCache) {
        if (!level.hasChunk(x >> 4, z >> 4) || roads.isInsideBuilding(x, z)
                || roads.streetByColumn().containsKey(columnKey(x, z))) {
            return null;
        }
        SurfaceSample sample = surfaceCache.get(x, z);
        if (sample == null || sample.pos().getY() > deckY || isConnectorSurface(sample.state())) {
            return null;
        }
        FluidState fluid = sample.state().getFluidState();
        if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) {
            return null;
        }
        for (int y = deckY + 1; y <= deckY + 2; y++) {
            BlockPos headroom = new BlockPos(x, y, z);
            if (!level.getBlockState(headroom).getCollisionShape(level, headroom).isEmpty()) {
                return null;
            }
        }
        return sample;
    }

    /**
     * Returns the post blocks from {@code topY} down to solid ground, an empty
     * list when the deck already rests on ground, or null when no ground lies
     * within {@link #MAX_POST_DEPTH}.
     */
    @Nullable
    private List<BlockPos> postColumn(ServerLevel level, int x, int topY, int z) {
        List<BlockPos> post = new ArrayList<>();
        for (int y = topY; y > topY - MAX_POST_DEPTH; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) {
                return null;
            }
            if (!state.isAir() && fluid.isEmpty() && !state.canBeReplaced()) {
                return post;
            }
            post.add(pos);
        }
        return null;
    }

    private static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isDryGap(SurfaceSample sample, int pathY) {
        return sample.state().getFluidState().isEmpty()
                && sample.pos().getY() <= pathY - MIN_GAP_DROP;
    }

    private static int rowX(int x, Direction travelDirection, int widthOffset) {
        return travelDirection.getAxis() == Direction.Axis.X ? x : x + widthOffset;
    }

    private static int rowZ(int z, Direction travelDirection, int widthOffset) {
        return travelDirection.getAxis() == Direction.Axis.X ? z + widthOffset : z;
    }

    @Nullable
    private PlacementPlan createPlacementPlan(ServerLevel level, List<RouteCell> route,
                                              List<RouteFeature> features,
                                              int fixedEntranceRows,
                                              Direction entranceDirection,
                                              PreparedVillageRoads roads,
                                              SurfaceCache surfaceCache,
                                              int pathWidth,
                                              VillagePathBlocks pathBlocks,
                                              PlacementRejection rejection) {
        if (route.size() < 2) {
            rejection.set("ROUTE_TOO_SHORT", null);
            return null;
        }

        List<BlockPos> centers = route.stream().map(RouteCell::pos).toList();
        Map<BlockPos, PlannedPathCell> cells = new LinkedHashMap<>();
        Map<Long, PlannedColumn> columns = new HashMap<>();
        // The final center is an existing street block and remains untouched.
        for (int routeIndex = 0; routeIndex < route.size() - 1; routeIndex++) {
            // Ramp and bridge rows were planned with their move; add them below.
            if (route.get(routeIndex).kind() != CellKind.PATH) {
                continue;
            }
            BlockPos center = centers.get(routeIndex);
            Direction direction = routeDirection(centers, routeIndex, entranceDirection);

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
                    rejection.set(routeIndex < fixedEntranceRows
                            ? "ENTRANCE_ROW_INSIDE_BUILDING" : "INSIDE_BUILDING", x, z);
                    return null;
                }
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    rejection.set("CHUNK_UNAVAILABLE", x, z);
                    return null;
                }

                boolean fixedEntranceRow = routeIndex < fixedEntranceRows;
                SurfaceSample sample = fixedEntranceRow
                        ? fixedEntranceSurface(level, x, center.getY(), z)
                        : surfaceCache.get(x, z);
                if (sample == null) {
                    rejection.set(fixedEntranceRow ? "ENTRANCE_ROW_FLUID" : "NO_SURFACE", x, z);
                    return null;
                }
                if (!fixedEntranceRow && !isUsablePathSurface(sample.state())) {
                    rejection.set("UNUSABLE_SURFACE", x, z);
                    return null;
                }

                // Preserve actual street blocks after the entrance prefix. A
                // street piece's bounding box also contains terrain, so skipping
                // the whole box would leave a grass gap before the real road.
                // Fixed entrance rows are intentionally retained in the plan:
                // bank placement grades and clears that apron after this plan is
                // created, so those road blocks must be restored afterward.
                if (!fixedEntranceRow && (isStreetSurface(sample.state().getBlock())
                        || isConnectorSurface(sample.state()))) {
                    continue;
                }

                if (cells.putIfAbsent(sample.pos(), new PlannedPathCell(sample.pos(), sample.snowPos(),
                        CellKind.PATH, sample.pos().getY())) == null) {
                    columns.putIfAbsent(columnKey(x, z), new PlannedColumn(sample.pos().getY(),
                            direction, !fixedEntranceRow));
                }
            }
        }

        List<PlannedDecoration> decorations = new ArrayList<>();
        for (RouteFeature feature : features) {
            for (PlannedPathCell cell : feature.cells()) {
                int x = cell.surfacePos().getX();
                int z = cell.surfacePos().getZ();
                long key = columnKey(x, z);
                PlannedColumn existing = columns.get(key);
                if (existing != null && existing.y() != cell.surfacePos().getY()) {
                    rejection.set("FEATURE_CROSSES_PATH", x, z);
                    return null;
                }
                cells.put(cell.surfacePos(), cell);
                columns.put(key, new PlannedColumn(cell.surfacePos().getY(), feature.direction(),
                        cell.kind() == CellKind.GRADED));
            }
            decorations.addAll(feature.decorations());
        }
        addStepDecorations(level, cells.values(), columns, roads, pathBlocks, decorations);
        return new PlacementPlan(new ArrayList<>(cells.values()), decorations);
    }

    /**
     * Softens each one-block rise along the path: a stair on the lower column
     * faces the higher neighbour, and a column lower than both neighbours
     * along its row gets a bottom slab. Bridge decks and fixed entrance rows
     * are never decorated. Street columns count as neighbours.
     */
    private void addStepDecorations(ServerLevel level, java.util.Collection<PlannedPathCell> cells,
                                    Map<Long, PlannedColumn> columns, PreparedVillageRoads roads,
                                    VillagePathBlocks pathBlocks, List<PlannedDecoration> decorations) {
        if (pathBlocks.stairBlock() == null || pathBlocks.slabBlock() == null) {
            return;
        }
        for (PlannedPathCell cell : cells) {
            int x = cell.surfacePos().getX();
            int z = cell.surfacePos().getZ();
            PlannedColumn column = columns.get(columnKey(x, z));
            if (column == null || !column.decoratable() || cell.kind() == CellKind.DECK
                    || column.y() != cell.surfacePos().getY()) {
                continue;
            }
            Direction direction = column.direction();
            int y = column.y();
            boolean higherAhead = neighbourSurfaceY(columns, roads,
                    x + direction.getStepX(), z + direction.getStepZ()) == y + 1;
            boolean higherBehind = neighbourSurfaceY(columns, roads,
                    x - direction.getStepX(), z - direction.getStepZ()) == y + 1;
            if (!higherAhead && !higherBehind) {
                continue;
            }
            BlockPos stepPos = cell.surfacePos().above();
            // A ramp cut clears terrain at or below its natural surface during placement.
            BlockState existing = level.getBlockState(stepPos);
            if (stepPos.getY() > cell.naturalY() && (!existing.getFluidState().isEmpty()
                    || (!existing.isAir() && !existing.canBeReplaced()))) {
                continue;
            }
            if (higherAhead && higherBehind) {
                decorations.add(new PlannedDecoration(stepPos, DecorationKind.SLAB, direction));
            } else {
                decorations.add(new PlannedDecoration(stepPos, DecorationKind.STAIR,
                        higherAhead ? direction : direction.getOpposite()));
            }
        }
    }

    private static int neighbourSurfaceY(Map<Long, PlannedColumn> columns, PreparedVillageRoads roads,
                                         int x, int z) {
        long key = columnKey(x, z);
        PlannedColumn column = columns.get(key);
        if (column != null) {
            return column.y();
        }
        StreetCell street = roads.streetByColumn().get(key);
        return street != null ? street.pos().getY() : Integer.MIN_VALUE;
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

    private void applyPlacementPlan(ServerLevel level, PlannedPath path) {
        VillagePathBlocks pathBlocks = path.pathBlocks;
        BlockState surfaceState = pathBlocks.surfaceBlock().defaultBlockState();
        BlockState supportState = pathBlocks.supportBlock().defaultBlockState();
        BlockState fillState = pathBlocks.fillBlock().defaultBlockState();

        // A path block under a stair or slab would revert, so use the fill block there.
        Set<BlockPos> underSteps = new HashSet<>();
        for (PlannedDecoration decoration : path.decorations) {
            if (decoration.kind() == DecorationKind.STAIR || decoration.kind() == DecorationKind.SLAB) {
                underSteps.add(decoration.pos().below());
            }
        }

        for (PlannedPathCell cell : path.cells) {
            BlockState top = underSteps.contains(cell.surfacePos()) ? fillState : surfaceState;
            switch (cell.kind()) {
                case PATH -> placePathCell(level, cell, top, supportState);
                case GRADED -> placeGradedCell(level, cell, top, fillState);
                case DECK -> placeDeckCell(level, cell, path.bridgeBlocks.deck());
            }
        }

        for (PlannedDecoration decoration : path.decorations) {
            if (decoration.kind() == DecorationKind.POST) {
                BlockState existing = level.getBlockState(decoration.pos());
                if (existing.isAir() || existing.canBeReplaced() || !existing.getFluidState().isEmpty()) {
                    level.setBlock(decoration.pos(), path.bridgeBlocks.post(), 2);
                }
            }
        }
        // Shaped blocks go last so stairs and railings connect to their final neighbours.
        for (PlannedDecoration decoration : path.decorations) {
            BlockState state = switch (decoration.kind()) {
                case STAIR -> stairState(pathBlocks.stairBlock(), decoration.facing());
                case SLAB -> pathBlocks.slabBlock() == null ? null : pathBlocks.slabBlock().defaultBlockState();
                case RAIL -> path.bridgeBlocks.rail();
                case POST -> null;
            };
            if (state != null) {
                placeShapedBlock(level, decoration.pos(), state);
            }
        }
    }

    private static void placePathCell(ServerLevel level, PlannedPathCell cell, BlockState top,
                                      BlockState supportState) {
        clearSnow(level, cell);
        if (level.getBlockState(cell.surfacePos()) != top) {
            level.setBlock(cell.surfacePos(), top, 2);
        }
        BlockPos below = cell.surfacePos().below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.isAir() || !belowState.getFluidState().isEmpty()) {
            level.setBlock(below, supportState, 2);
        }
    }

    /** Cuts or fills one ramp column to its planned height and clears its headroom. */
    private static void placeGradedCell(ServerLevel level, PlannedPathCell cell, BlockState top,
                                        BlockState fillState) {
        clearSnow(level, cell);
        BlockPos surface = cell.surfacePos();
        int targetY = surface.getY();
        int naturalY = cell.naturalY();
        for (int y = naturalY + 1; y < targetY; y++) {
            level.setBlock(surface.atY(y), fillState, 2);
        }
        int clearTop = Math.max(naturalY + 1, targetY + 2);
        for (int y = targetY + 1; y <= clearTop; y++) {
            BlockPos pos = surface.atY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.getFluidState().isEmpty()
                    && (y <= naturalY || state.canBeReplaced())) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        level.setBlock(surface, top, 2);
        BlockState belowState = level.getBlockState(surface.below());
        if (belowState.isAir() || !belowState.getFluidState().isEmpty()) {
            level.setBlock(surface.below(), fillState, 2);
        }
    }

    private static void placeDeckCell(ServerLevel level, PlannedPathCell cell, BlockState deck) {
        clearSnow(level, cell);
        level.setBlock(cell.surfacePos(), deck, 2);
        for (int y = 1; y <= 2; y++) {
            BlockPos pos = cell.surfacePos().above(y);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.getFluidState().isEmpty() && state.canBeReplaced()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private static void clearSnow(ServerLevel level, PlannedPathCell cell) {
        if (cell.snowPos() != null && level.getBlockState(cell.snowPos()).is(Blocks.SNOW)) {
            level.setBlock(cell.snowPos(), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    @Nullable
    private static BlockState stairState(@Nullable Block stairBlock, Direction facing) {
        if (stairBlock == null) {
            return null;
        }
        BlockState state = stairBlock.defaultBlockState();
        return state.hasProperty(StairBlock.FACING) ? state.setValue(StairBlock.FACING, facing) : state;
    }

    private static void placeShapedBlock(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState existing = level.getBlockState(pos);
        if (!existing.getFluidState().isEmpty() || (!existing.isAir() && !existing.canBeReplaced())) {
            return;
        }
        level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), 2);
    }

    private static boolean endpointChunksLoaded(ServerLevel level, BlockPos start, BlockPos target) {
        return level.hasChunk(start.getX() >> 4, start.getZ() >> 4)
                && level.hasChunk(target.getX() >> 4, target.getZ() >> 4);
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

    /**
     * Stairs, slabs and bridge decks from an earlier connector. A later route may
     * walk over them, but never repaves, grades or bridges them.
     */
    private static boolean isConnectorSurface(BlockState state) {
        Block block = state.getBlock();
        return VillagePathBlocks.isPathStep(block) || VillageBridgeBlocks.isDeck(block);
    }

    private boolean isUsablePathSurface(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (isConnectorSurface(state)) {
            return true;
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

    private static int nearestTargetDistance(int x, int z, int[] targetXs, int[] targetZs) {
        int nearest = Integer.MAX_VALUE;
        for (int index = 0; index < targetXs.length; index++) {
            nearest = Math.min(nearest, Math.abs(targetXs[index] - x) + Math.abs(targetZs[index] - z));
        }
        return nearest;
    }

    private static int directionOrder(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case SOUTH -> 1;
            case WEST -> 2;
            case EAST -> 3;
            default -> direction.ordinal();
        };
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
            if (y < com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.min(level)) {
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

        private void forgetUnavailableColumns() {
            invalidColumns.clear();
        }
    }

    private record SurfaceSample(BlockPos pos, BlockState state, @Nullable BlockPos snowPos) {
    }

    private record StreetTarget(BlockPos pos, Block surfaceBlock, long score) {
    }

    private record StreetTargetSearch(List<StreetTarget> targets, int inRangeCount,
                                      int largestNetworkSize) {
    }

    private enum RouteOutcome {
        FOUND, START_UNAVAILABLE, VISITED_CAP, EXHAUSTED_WITH_STEP_PRUNING,
        BLOCKED, RECONSTRUCT_FAILED, TIME_LIMIT
    }

    private record RouteSearch(List<RouteCell> route, List<RouteFeature> features,
                               @Nullable StreetTarget target, RouteOutcome outcome, int visited,
                               String firstStepRejections) {
    }

    /** How a planned column is built: plain path, a graded ramp column, or a bridge deck. */
    private enum CellKind { PATH, GRADED, DECK }

    private enum DecorationKind { STAIR, SLAB, POST, RAIL }

    private record RouteCell(BlockPos pos, CellKind kind) {
    }

    /**
     * A ramp or bridge between two route nodes. {@code centers} are its
     * intermediate route columns in travel order; {@code cells} cover its full
     * width, including bridge railing columns.
     */
    private record RouteFeature(CellKind kind, Direction direction, List<BlockPos> centers,
                                List<PlannedPathCell> cells, List<PlannedDecoration> decorations) {
    }

    private record FeatureMove(RouteFeature feature, SurfaceSample landing, int columns, int cost) {
    }

    private record SearchBounds(int minX, int maxX, int minZ, int maxZ) {
        private boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private record PlannedColumn(int y, Direction direction, boolean decoratable) {
    }

    private record PlannedDecoration(BlockPos pos, DecorationKind kind, Direction facing) {
    }

    private record PlacementPlan(List<PlannedPathCell> cells, List<PlannedDecoration> decorations) {
    }

    /** Last placement-plan rejection, reused across targets of one connection. */
    private static final class PlacementRejection {
        private String reason = "NONE";
        private String column = "-";

        private void set(String reason, int x, int z) {
            this.reason = reason;
            this.column = x + "," + z;
        }

        private void set(String reason, @Nullable String column) {
            this.reason = reason;
            this.column = column == null ? "-" : column;
        }
    }

    private record RouteKey(int x, int z, Direction incomingDirection) {
    }

    private record OpenRouteNode(RouteKey key, int estimatedTotalCost) {
    }

    private record PlannedPathCell(BlockPos surfacePos, @Nullable BlockPos snowPos, CellKind kind,
                                   int naturalY) {
    }

    private record StreetCell(BlockPos pos) {
    }

    public static final class PreparedVillageRoads {
        private final Map<Long, StreetCell> streetByColumn;
        private final Map<Long, List<BoundingBox>> buildingsByChunk;
        @Nullable
        private final VillageBridgeBlocks bridgeBlocks;

        private PreparedVillageRoads(Map<Long, StreetCell> streetByColumn,
                                     Map<Long, List<BoundingBox>> buildingsByChunk,
                                     @Nullable VillageBridgeBlocks bridgeBlocks) {
            this.streetByColumn = streetByColumn;
            this.buildingsByChunk = buildingsByChunk;
            this.bridgeBlocks = bridgeBlocks;
        }

        /** Fixes this village's bridge materials for every connector planned from the snapshot. */
        public PreparedVillageRoads withBridgeBlocks(VillageBridgeBlocks blocks) {
            return new PreparedVillageRoads(streetByColumn, buildingsByChunk, blocks);
        }

        private VillageBridgeBlocks bridgeBlocks(String biomeType) {
            return bridgeBlocks != null ? bridgeBlocks : VillageBridgeBlocks.forBiome(biomeType);
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

        /** Diagnostic-only: the first building box containing the column. */
        private String buildingAt(int x, int z) {
            List<BoundingBox> boxes = buildingsByChunk.get(columnKey(x >> 4, z >> 4));
            if (boxes != null) {
                for (BoundingBox box : boxes) {
                    if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                        return "[" + box.minX() + "," + box.minZ() + ".." + box.maxX() + "," + box.maxZ() + "]";
                    }
                }
            }
            return "[]";
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
            return new PreparedVillageRoads(streetByColumn, merged, bridgeBlocks);
        }

        /**
         * Removes one exact exclusion claim while retaining all other building
         * obstacles. This is used when a building's reservation includes a
         * deliberate approach area that its connector must pave.
         */
        public PreparedVillageRoads withoutBuilding(BoundingBox excludedBox) {
            Map<Long, List<BoundingBox>> filtered = new HashMap<>();
            buildingsByChunk.forEach((key, boxes) -> {
                List<BoundingBox> remaining = boxes.stream()
                        .filter(box -> !sameBounds(box, excludedBox))
                        .toList();
                if (!remaining.isEmpty()) {
                    filtered.put(key, remaining);
                }
            });
            return new PreparedVillageRoads(streetByColumn, filtered, bridgeBlocks);
        }

        private static boolean sameBounds(BoundingBox first, BoundingBox second) {
            return first.minX() == second.minX() && first.minY() == second.minY()
                    && first.minZ() == second.minZ() && first.maxX() == second.maxX()
                    && first.maxY() == second.maxY() && first.maxZ() == second.maxZ();
        }
    }

    /** Immutable path plan whose occupied surface columns can be reserved before placement. */
    public static final class PlannedPath {
        private final List<PlannedPathCell> cells;
        private final List<PlannedDecoration> decorations;
        private final VillagePathBlocks pathBlocks;
        private final VillageBridgeBlocks bridgeBlocks;
        private final String connectionType;
        private final int pathWidth;
        private final BlockPos pathStart;
        private final BlockPos target;

        private PlannedPath(List<PlannedPathCell> cells, List<PlannedDecoration> decorations,
                            VillagePathBlocks pathBlocks, VillageBridgeBlocks bridgeBlocks,
                            String connectionType, int pathWidth,
                            BlockPos pathStart, BlockPos target) {
            this.cells = cells;
            this.decorations = decorations;
            this.pathBlocks = pathBlocks;
            this.bridgeBlocks = bridgeBlocks;
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
