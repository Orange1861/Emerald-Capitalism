package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.util.LoadedChunkComposition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds bounded, harvestable tree snapshots for a lumberjack goal. */
final class LumberjackTreeScanner {

    static final int INITIAL_SEARCH_RANGE = 24;
    static final int MAX_SEARCH_RANGE = 72;
    static final int SEARCH_RANGE_INCREMENT = 24;
    private static final int VERTICAL_SEARCH_RANGE = 16;
    private static final int HOME_PROTECTION_HORIZONTAL_RADIUS = 8;
    private static final int HOME_PROTECTION_VERTICAL_RADIUS = 6;
    private static final int JOB_SITE_PROTECTION_HORIZONTAL_RADIUS = 14;
    private static final int JOB_SITE_PROTECTION_VERTICAL_RADIUS = 8;
    private static final TagKey<Structure> VILLAGE_STRUCTURE_TAG = TagKey.create(
            Registries.STRUCTURE,
            ResourceLocation.fromNamespaceAndPath("minecraft", "village"));
    private static final int MIN_LOGS = 3;
    private static final int MIN_LEAVES = 4;
    private static final int MAX_LOGS = 96;
    private static final int MAX_LEAVES = 160;
    private static final int CANOPY_HORIZONTAL_PADDING = 4;
    private static final int CANOPY_VERTICAL_PADDING = 4;

    private final Villager villager;

    LumberjackTreeScanner(Villager villager) {
        this.villager = villager;
    }

    @Nullable
    TreeSnapshot findNearestTree(ServerLevel level) {
        return findCandidateTrees(level, INITIAL_SEARCH_RANGE).stream().findFirst().orElse(null);
    }

    List<TreeSnapshot> findCandidateTrees(ServerLevel level, int requestedSearchRange) {
        int searchRange = Math.max(INITIAL_SEARCH_RANGE,
                Math.min(MAX_SEARCH_RANGE, requestedSearchRange));
        BlockPos origin = villager.blockPosition();
        LoadedChunkComposition composition = LoadedChunkComposition.find(
                level,
                origin.getX() - searchRange, origin.getX() + searchRange,
                origin.getY() - VERTICAL_SEARCH_RANGE, origin.getY() + VERTICAL_SEARCH_RANGE,
                origin.getZ() - searchRange, origin.getZ() + searchRange,
                this::isLumberjackLog);
        if (composition.isEmpty()) {
            return List.of();
        }

        // Remove disconnected/dead lumberjacks once for the whole search. The
        // old implementation performed this cleanup for every candidate tree.
        LumberjackTreeReservations.prune(level);
        List<TreeSnapshot> candidates = new ArrayList<>();
        Set<BlockPos> examinedLogs = new HashSet<>();
        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();

        // Search the complete requested radius and return every valid tree. The
        // goal can then discard unreachable trees and try the next nearest one
        // instead of treating the first pathfinding failure as a failed search.
        for (int shell = 0; shell <= searchRange; shell++) {
            for (int x = -shell; x <= shell; x++) {
                for (int z = -shell; z <= shell; z++) {
                    if (shell > 0 && Math.abs(x) != shell && Math.abs(z) != shell) {
                        continue;
                    }
                    for (int y = -VERTICAL_SEARCH_RANGE; y <= VERTICAL_SEARCH_RANGE; y++) {
                        candidate.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        if (!composition.mayContain(candidate)) {
                            continue;
                        }
                        BlockState candidateState = composition.getBlockStateIfLoaded(candidate);
                        if (!isLumberjackLog(candidateState)) {
                            continue;
                        }

                        BlockPos immutableCandidate = candidate.immutable();
                        if (examinedLogs.contains(immutableCandidate)) {
                            continue;
                        }
                        if (LumberjackTreeReservations.isLogReservedByOther(
                                level, villager.getUUID(), immutableCandidate)) {
                            continue;
                        }

                        double distanceSq = origin.distSqr(immutableCandidate);
                        if (distanceSq > (double) searchRange * searchRange) {
                            continue;
                        }

                        TreeSnapshot found = scanTree(level, immutableCandidate, examinedLogs);
                        if (found != null && !LumberjackTreeReservations.isLogReservedByOther(
                                level, villager.getUUID(), found.logs())) {
                            candidates.add(found);
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(tree -> origin.distSqr(tree.base())));
        return List.copyOf(candidates);
    }

    @Nullable
    TreeSnapshot findCandidateTreeAt(ServerLevel level, BlockPos seed) {
        BlockState state = getLoadedBlockState(level, seed);
        if (!isLumberjackLog(state)) {
            return null;
        }
        return scanTree(level, seed.immutable(), new HashSet<>());
    }

    boolean isNaturalLeaf(BlockState state) {
        return state != null && state.is(BlockTags.LEAVES)
                && (!state.hasProperty(LeavesBlock.PERSISTENT)
                || !state.getValue(LeavesBlock.PERSISTENT));
    }

    boolean isLumberjackLog(BlockState state) {
        return state != null && state.is(BlockTags.LOGS) && !isCherryLog(state);
    }

    @Nullable
    private TreeSnapshot scanTree(ServerLevel level, BlockPos seed, Set<BlockPos> examinedLogs) {
        Set<BlockPos> logs = connectedBlocks(level, seed, BlockTags.LOGS, MAX_LOGS);
        examinedLogs.addAll(logs);
        if (logs.stream().anyMatch(pos -> isProtectedBuildingLog(level, pos))) {
            return null;
        }
        if (logs.stream().anyMatch(pos -> {
            BlockState state = getLoadedBlockState(level, pos);
            return state == null || isCherryLog(state);
        })) {
            return null;
        }
        if (logs.size() < MIN_LOGS || logs.size() >= MAX_LOGS) {
            return null;
        }

        Item sapling = null;
        for (BlockPos log : logs) {
            BlockState logState = getLoadedBlockState(level, log);
            if (logState == null) {
                return null;
            }
            Item logSapling = saplingForLog(logState.getBlock());
            if (logSapling == null) {
                return null;
            }
            if (sapling == null) {
                sapling = logSapling;
            } else if (sapling != logSapling) {
                return null;
            }
        }

        Set<BlockPos> directLeaves = new HashSet<>();
        for (BlockPos log : logs) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos adjacent = log.offset(dx, dy, dz);
                        BlockState adjacentState = getLoadedBlockState(level, adjacent);
                        if (adjacentState != null && isNaturalLeaf(adjacentState)) {
                            directLeaves.add(adjacent.immutable());
                        }
                    }
                }
            }
        }
        if (directLeaves.isEmpty()) {
            return null;
        }

        BlockBounds logBounds = BlockBounds.around(logs);
        Set<BlockPos> leaves = connectedCanopy(level, directLeaves, logBounds, MAX_LEAVES);
        if (leaves.size() < MIN_LEAVES || leaves.size() >= MAX_LEAVES) {
            return null;
        }

        BlockPos base = logs.stream()
                .filter(pos -> {
                    BlockPos below = pos.below();
                    BlockState belowState = getLoadedBlockState(level, below);
                    return belowState != null && belowState.isFaceSturdy(level, below, Direction.UP);
                })
                .min(Comparator.comparingInt(BlockPos::getY))
                .orElse(null);
        if (base == null) {
            return null;
        }

        int verticalTrunkHeight = 1;
        while (logs.contains(base.above(verticalTrunkHeight))) {
            verticalTrunkHeight++;
        }
        if (verticalTrunkHeight < 2
                || directLeaves.stream().noneMatch(leaf -> leaf.getY() >= base.getY() + 1)) {
            return null;
        }

        List<BlockPos> orderedLogs = new ArrayList<>(logs);
        orderedLogs.sort(Comparator.<BlockPos>comparingLong(pos -> pos.getY())
                .thenComparingLong(pos -> pos.getX())
                .thenComparingLong(pos -> pos.getZ()));
        return new TreeSnapshot(orderedLogs, leaves, base.immutable(), sapling);
    }

    /**
     * Village structure pieces are authoritative for generated houses. The home
     * and job-site bounds cover custom/player-built housing that has no structure
     * metadata, including the custom lumbermill template.
     */
    private boolean isProtectedBuildingLog(ServerLevel level, BlockPos pos) {
        StructureStart villageStructure = level.structureManager()
                .getStructureWithPieceAt(pos, VILLAGE_STRUCTURE_TAG);
        if (villageStructure != null && villageStructure.isValid()) {
            return true;
        }
        return isNearMemory(level, pos, MemoryModuleType.HOME,
                HOME_PROTECTION_HORIZONTAL_RADIUS, HOME_PROTECTION_VERTICAL_RADIUS)
                || isNearMemory(level, pos, MemoryModuleType.JOB_SITE,
                JOB_SITE_PROTECTION_HORIZONTAL_RADIUS, JOB_SITE_PROTECTION_VERTICAL_RADIUS);
    }

    private boolean isNearMemory(ServerLevel level, BlockPos pos,
                                 MemoryModuleType<GlobalPos> memoryType,
                                 int horizontalRadius, int verticalRadius) {
        return villager.getBrain().getMemory(memoryType)
                .filter(globalPos -> globalPos.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .stream()
                .anyMatch(anchor -> Math.abs(pos.getX() - anchor.getX()) <= horizontalRadius
                        && Math.abs(pos.getY() - anchor.getY()) <= verticalRadius
                        && Math.abs(pos.getZ() - anchor.getZ()) <= horizontalRadius);
    }

    private Set<BlockPos> connectedBlocks(ServerLevel level, BlockPos seed,
                                          TagKey<Block> tag, int limit) {
        return connectedBlocks(level, Set.of(seed), tag, limit);
    }

    private Set<BlockPos> connectedBlocks(ServerLevel level, Set<BlockPos> seeds,
                                          TagKey<Block> tag, int limit) {
        Set<BlockPos> found = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos seed : seeds) {
            BlockState seedState = getLoadedBlockState(level, seed);
            if (seedState != null && seedState.is(tag) && found.add(seed.immutable())) {
                queue.add(seed.immutable());
            }
        }

        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                BlockState nextState = getLoadedBlockState(level, next);
                if (nextState != null && nextState.is(tag) && found.add(next.immutable())) {
                    queue.addLast(next.immutable());
                    if (found.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return found;
    }

    private Set<BlockPos> connectedCanopy(ServerLevel level, Set<BlockPos> seeds,
                                          BlockBounds logBounds, int limit) {
        Set<BlockPos> found = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos seed : seeds) {
            BlockState seedState = getLoadedBlockState(level, seed);
            if (seedState != null && isNaturalLeaf(seedState)
                    && logBounds.contains(seed, CANOPY_HORIZONTAL_PADDING, CANOPY_VERTICAL_PADDING)
                    && found.add(seed.immutable())) {
                queue.add(seed.immutable());
            }
        }

        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                BlockState nextState = getLoadedBlockState(level, next);
                if (nextState != null && isNaturalLeaf(nextState)
                        && logBounds.contains(next, CANOPY_HORIZONTAL_PADDING, CANOPY_VERTICAL_PADDING)
                        && found.add(next.immutable())) {
                    queue.addLast(next.immutable());
                    if (found.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return found;
    }

    @Nullable
    private BlockState getLoadedBlockState(ServerLevel level, BlockPos pos) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return null;
        }
        return level.getBlockState(pos);
    }

    private boolean isCherryLog(BlockState state) {
        return state.is(Blocks.CHERRY_LOG) || state.is(Blocks.STRIPPED_CHERRY_LOG);
    }

    @Nullable
    private Item saplingForLog(Block log) {
        if (log == Blocks.OAK_LOG) {
            return Items.OAK_SAPLING;
        }
        if (log == Blocks.SPRUCE_LOG) {
            return Items.SPRUCE_SAPLING;
        }
        if (log == Blocks.BIRCH_LOG) {
            return Items.BIRCH_SAPLING;
        }
        if (log == Blocks.JUNGLE_LOG) {
            return Items.JUNGLE_SAPLING;
        }
        if (log == Blocks.ACACIA_LOG) {
            return Items.ACACIA_SAPLING;
        }
        if (log == Blocks.DARK_OAK_LOG) {
            return Items.DARK_OAK_SAPLING;
        }
        if (log == Blocks.MANGROVE_LOG) {
            return Items.MANGROVE_PROPAGULE;
        }
        return null;
    }

    record TreeSnapshot(List<BlockPos> logs, Set<BlockPos> leaves, BlockPos base, Item sapling) {
    }

    private record BlockBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {

        private static BlockBounds around(Set<BlockPos> positions) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : positions) {
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY());
                maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            return new BlockBounds(minX, maxX, minY, maxY, minZ, maxZ);
        }

        private boolean contains(BlockPos pos, int horizontalPadding, int verticalPadding) {
            return pos.getX() >= minX - horizontalPadding && pos.getX() <= maxX + horizontalPadding
                    && pos.getY() >= minY - verticalPadding && pos.getY() <= maxY + verticalPadding
                    && pos.getZ() >= minZ - horizontalPadding && pos.getZ() <= maxZ + horizontalPadding;
        }
    }
}
