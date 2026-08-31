package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime index of saplings planted by lumberjacks and by generated lumbermills.
 * The cache is intentionally level-scoped and disposable; the world remains
 * authoritative and every entry is checked against the live block state.
 */
public final class LumberjackSaplingCache {

    private static final Map<ServerLevel, Map<UUID, Map<BlockPos, Block>>> PLANTED_SAPLINGS =
            new IdentityHashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, Block>> LUMBERMILL_SAPLINGS =
            new IdentityHashMap<>();

    private LumberjackSaplingCache() {
    }

    public record Candidate(BlockPos position, Block saplingBlock) {
    }

    /** Records a sapling successfully planted by this lumberjack. */
    public static void trackPlaced(ServerLevel level, UUID ownerId, BlockPos position, Block saplingBlock) {
        if (!saplingBlock.defaultBlockState().is(BlockTags.SAPLINGS)) {
            return;
        }
        BlockPos immutablePosition = position.immutable();
        Map<BlockPos, Block> lumbermill = LUMBERMILL_SAPLINGS.get(level);
        if (lumbermill != null) {
            lumbermill.remove(immutablePosition);
            if (lumbermill.isEmpty()) {
                LUMBERMILL_SAPLINGS.remove(level);
            }
        }
        PLANTED_SAPLINGS.computeIfAbsent(level, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(ownerId, ignored -> new LinkedHashMap<>())
                .put(immutablePosition, saplingBlock);
    }

    /**
     * Indexes saplings in a just-placed lumbermill template. These entries are
     * shared until one lumberjack claims the grown tree.
     */
    public static void trackLumbermillSaplings(ServerLevel level, BoundingBox placementBox) {
        Map<BlockPos, Block> saplings = LUMBERMILL_SAPLINGS.computeIfAbsent(
                level, ignored -> new LinkedHashMap<>());
        for (BlockPos position : BlockPos.betweenClosed(
                new BlockPos(placementBox.minX(), placementBox.minY(), placementBox.minZ()),
                new BlockPos(placementBox.maxX(), placementBox.maxY(), placementBox.maxZ()))) {
            if (!level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) {
                continue;
            }
            BlockState state = level.getBlockState(position);
            if (state.is(BlockTags.SAPLINGS)) {
                saplings.put(position.immutable(), state.getBlock());
            }
        }
    }

    /**
     * Returns cached positions that have become logs. Original saplings are
     * removed when replaced, while unrelated blocks are not allowed to keep a
     * stale cache entry alive. The caller still runs the exact tree validator.
     */
    public static List<Candidate> findGrownSaplings(ServerLevel level, UUID ownerId,
                                                     BlockPos origin, int horizontalRange,
                                                     int verticalRange) {
        Map<BlockPos, Block> candidates = new LinkedHashMap<>();
        Map<UUID, Map<BlockPos, Block>> byOwner = PLANTED_SAPLINGS.get(level);
        if (byOwner != null) {
            Map<BlockPos, Block> owned = byOwner.get(ownerId);
            if (owned != null) {
                collectGrown(level, origin, horizontalRange, verticalRange, owned, candidates);
            }
            if (byOwner.isEmpty()) {
                PLANTED_SAPLINGS.remove(level);
            }
        }
        Map<BlockPos, Block> lumbermill = LUMBERMILL_SAPLINGS.get(level);
        if (lumbermill != null) {
            collectGrown(level, origin, horizontalRange, verticalRange, lumbermill, candidates);
            if (lumbermill.isEmpty()) {
                LUMBERMILL_SAPLINGS.remove(level);
            }
        }

        List<Candidate> result = new ArrayList<>(candidates.size());
        candidates.forEach((position, saplingBlock) -> result.add(new Candidate(position, saplingBlock)));
        result.sort(Comparator.comparingDouble(candidate -> origin.distSqr(candidate.position())));
        return result;
    }

    /** Removes cache entries once their corresponding tree has been harvested or invalidated. */
    public static void forget(ServerLevel level, UUID ownerId, BlockPos position) {
        Map<UUID, Map<BlockPos, Block>> byOwner = PLANTED_SAPLINGS.get(level);
        if (byOwner != null) {
            Map<BlockPos, Block> owned = byOwner.get(ownerId);
            if (owned != null) {
                owned.remove(position);
                if (owned.isEmpty()) {
                    byOwner.remove(ownerId);
                }
            }
            if (byOwner.isEmpty()) {
                PLANTED_SAPLINGS.remove(level);
            }
        }
        Map<BlockPos, Block> lumbermill = LUMBERMILL_SAPLINGS.get(level);
        if (lumbermill != null) {
            lumbermill.remove(position);
            if (lumbermill.isEmpty()) {
                LUMBERMILL_SAPLINGS.remove(level);
            }
        }
    }

    public static void clear(ServerLevel level) {
        PLANTED_SAPLINGS.remove(level);
        LUMBERMILL_SAPLINGS.remove(level);
    }

    public static void clearOwner(ServerLevel level, UUID ownerId) {
        Map<UUID, Map<BlockPos, Block>> byOwner = PLANTED_SAPLINGS.get(level);
        if (byOwner != null) {
            byOwner.remove(ownerId);
            if (byOwner.isEmpty()) {
                PLANTED_SAPLINGS.remove(level);
            }
        }
    }

    public static void clearAll() {
        PLANTED_SAPLINGS.clear();
        LUMBERMILL_SAPLINGS.clear();
    }

    private static void collectGrown(ServerLevel level, BlockPos origin,
                                     int horizontalRange, int verticalRange,
                                     Map<BlockPos, Block> tracked,
                                     Map<BlockPos, Block> candidates) {
        Iterator<Map.Entry<BlockPos, Block>> iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Block> entry = iterator.next();
            BlockPos position = entry.getKey();
            if (!isInRange(origin, position, horizontalRange, verticalRange)) {
                continue;
            }
            if (!level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) {
                continue;
            }
            BlockState state = level.getBlockState(position);
            if (state.is(entry.getValue())) {
                continue;
            }
            if (!state.is(BlockTags.SAPLINGS)) {
                if (state.is(BlockTags.LOGS)) {
                    candidates.putIfAbsent(position, entry.getValue());
                } else {
                    iterator.remove();
                }
                continue;
            }
            // A sapling block changed species or state; the original planting
            // is no longer the block this cache entry describes.
            if (state.getBlock() != entry.getValue()) {
                iterator.remove();
            }
        }
    }

    private static boolean isInRange(BlockPos origin, BlockPos position,
                                     int horizontalRange, int verticalRange) {
        int dx = origin.getX() - position.getX();
        int dz = origin.getZ() - position.getZ();
        return (long) dx * dx + (long) dz * dz <= (long) horizontalRange * horizontalRange
                && Math.abs(origin.getY() - position.getY()) <= verticalRange;
    }

}
