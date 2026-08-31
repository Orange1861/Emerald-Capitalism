package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds bounded, harvestable tree snapshots for a lumberjack goal. */
final class LumberjackTreeScanner {

    private static final int SEARCH_RANGE = 24;
    private static final int VERTICAL_SEARCH_RANGE = 10;
    private static final int MIN_LOGS = 3;
    private static final int MIN_LEAVES = 4;
    private static final int MAX_LOGS = 96;
    private static final int MAX_LEAVES = 160;
    private static final int CANOPY_HORIZONTAL_PADDING = 4;
    private static final int CANOPY_VERTICAL_PADDING = 4;
    private static final int MAX_TREE_DISTANCE_SQ = SEARCH_RANGE * SEARCH_RANGE;

    private final Villager villager;

    LumberjackTreeScanner(Villager villager) {
        this.villager = villager;
    }

    @Nullable
    TreeSnapshot findNearestTree(ServerLevel level) {
        BlockPos origin = villager.blockPosition();
        TreeSnapshot nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        Set<BlockPos> examinedLogs = new HashSet<>();
        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();

        // Expand horizontally from the villager. Once a valid tree is found,
        // shells farther away than that tree cannot improve the result.
        for (int shell = 0; shell <= SEARCH_RANGE; shell++) {
            if (nearest != null && shell * shell > nearestDistanceSq) {
                break;
            }
            for (int x = -shell; x <= shell; x++) {
                for (int z = -shell; z <= shell; z++) {
                    if (shell > 0 && Math.abs(x) != shell && Math.abs(z) != shell) {
                        continue;
                    }
                    for (int y = -VERTICAL_SEARCH_RANGE; y <= VERTICAL_SEARCH_RANGE; y++) {
                        candidate.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        if (!isLumberjackLog(level.getBlockState(candidate))) {
                            continue;
                        }

                        BlockPos immutableCandidate = candidate.immutable();
                        if (examinedLogs.contains(immutableCandidate)) {
                            continue;
                        }

                        double distanceSq = origin.distSqr(immutableCandidate);
                        if (distanceSq >= nearestDistanceSq || distanceSq > MAX_TREE_DISTANCE_SQ) {
                            continue;
                        }

                        TreeSnapshot found = scanTree(level, immutableCandidate, examinedLogs);
                        if (found != null && !LumberjackTreeReservations.isReservedByOther(
                                level, villager.getUUID(), found.logs())) {
                            nearest = found;
                            nearestDistanceSq = distanceSq;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    boolean isNaturalLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES)
                && (!state.hasProperty(LeavesBlock.PERSISTENT)
                || !state.getValue(LeavesBlock.PERSISTENT));
    }

    boolean isLumberjackLog(BlockState state) {
        return state.is(BlockTags.LOGS) && !isCherryLog(state);
    }

    @Nullable
    private TreeSnapshot scanTree(ServerLevel level, BlockPos seed, Set<BlockPos> examinedLogs) {
        Set<BlockPos> logs = connectedBlocks(level, seed, BlockTags.LOGS, MAX_LOGS);
        examinedLogs.addAll(logs);
        if (logs.stream().anyMatch(pos -> isCherryLog(level.getBlockState(pos)))) {
            return null;
        }
        if (logs.size() < MIN_LOGS || logs.size() >= MAX_LOGS) {
            return null;
        }

        Item sapling = null;
        for (BlockPos log : logs) {
            Item logSapling = saplingForLog(level.getBlockState(log).getBlock());
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
                        if (isNaturalLeaf(level.getBlockState(adjacent))) {
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
                .filter(pos -> level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP))
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

    private Set<BlockPos> connectedBlocks(ServerLevel level, BlockPos seed,
                                          TagKey<Block> tag, int limit) {
        return connectedBlocks(level, Set.of(seed), tag, limit);
    }

    private Set<BlockPos> connectedBlocks(ServerLevel level, Set<BlockPos> seeds,
                                          TagKey<Block> tag, int limit) {
        Set<BlockPos> found = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos seed : seeds) {
            if (level.getBlockState(seed).is(tag) && found.add(seed.immutable())) {
                queue.add(seed.immutable());
            }
        }

        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (level.getBlockState(next).is(tag) && found.add(next.immutable())) {
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
            if (isNaturalLeaf(level.getBlockState(seed))
                    && logBounds.contains(seed, CANOPY_HORIZONTAL_PADDING, CANOPY_VERTICAL_PADDING)
                    && found.add(seed.immutable())) {
                queue.add(seed.immutable());
            }
        }

        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (isNaturalLeaf(level.getBlockState(next))
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
