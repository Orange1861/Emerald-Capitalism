package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Shared placement logic for the Village Manager block, used by both
 * automatic world-gen placement and manual player placement.
 */
public final class VillageManagerPlacement {

    private static final Direction[] CARDINAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private VillageManagerPlacement() {}

    /**
     * Finds a suitable position to place a Village Manager block near a bell.
     * <ol>
     *   <li>Checks each cardinal direction 1 block out from the bell at bell Y level</li>
     *   <li>Falls back to 1 Y level below with the same cardinal sweep</li>
     *   <li>Falls back to 2 Y levels below with the same cardinal sweep</li>
     *   <li>Expanded fallback: scans a 5x5 area around the bell at Y offsets -2..0</li>
     * </ol>
     *
     * @return a valid placement position, or null if none found
     */
    @Nullable
    public static BlockPos findPlacementNearBell(ServerLevel level, BlockPos bellPos) {
        // Pass 1: bell Y level, each cardinal direction
        for (Direction dir : CARDINAL) {
            BlockPos candidate = bellPos.relative(dir);
            if (isValidPlacement(level, candidate)) {
                return candidate;
            }
        }

        // Pass 2: one Y below bell, each cardinal direction
        for (Direction dir : CARDINAL) {
            BlockPos candidate = bellPos.below().relative(dir);
            if (isValidPlacement(level, candidate)) {
                return candidate;
            }
        }

        // Pass 3: two Y below bell, each cardinal direction
        for (Direction dir : CARDINAL) {
            BlockPos candidate = bellPos.below(2).relative(dir);
            if (isValidPlacement(level, candidate)) {
                return candidate;
            }
        }

        // Pass 4 fallback: search nearby ring positions (including diagonals)
        // with slight vertical offsets to handle tighter village templates.
        for (int radius = 1; radius <= 2; radius++) {
            for (int yOffset = 0; yOffset >= -2; yOffset--) {
                int y = bellPos.getY() + yOffset;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos candidate = new BlockPos(bellPos.getX() + dx, y, bellPos.getZ() + dz);
                        if (isValidPlacement(level, candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Finds a safe nearby spawn location for a villager around a placed Village Manager.
     * <p>
     * Search order favors tiles closest to the manager first, while allowing slight
     * vertical adjustment to handle uneven village terrain.
     *
     * @return a valid villager feet position, or null if no safe nearby tile exists
     */
    @Nullable
    public static BlockPos findSafeVillagerSpawnNear(ServerLevel level, BlockPos managerPos) {
        for (int radius = 1; radius <= 4; radius++) {
            for (int yOffset = -1; yOffset <= 1; yOffset++) {
                int y = managerPos.getY() + yOffset;

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos candidate = new BlockPos(managerPos.getX() + dx, y, managerPos.getZ() + dz);
                        if (isSafeVillagerSpawn(level, candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * A position is valid for placement if:
     * <ul>
     *   <li>The block at the position is replaceable (air, short grass, snow, etc.)</li>
     *   <li>The block below it is a solid full face (not air, water, slabs, etc.)</li>
     * </ul>
     */
    private static boolean isValidPlacement(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isReplaceable(state)) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        return below.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /**
     * Returns true if the block can be safely replaced by the manager block.
     */
    private static boolean isReplaceable(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        // canBeReplaced covers short grass, tall grass, snow layers, dead bushes, etc.
        return state.canBeReplaced();
    }

    private static boolean isSafeVillagerSpawn(ServerLevel level, BlockPos pos) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState below = level.getBlockState(pos.below());

        if (!isReplaceable(feet) || !isReplaceable(head)) {
            return false;
        }
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
            return false;
        }
        return below.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /**
     * Searches for the nearest bell block within the given radius of a center position.
     * Y-axis is clamped to ±16 blocks to avoid scanning excessive vertical space.
     *
     * @return the closest bell position, or null if none found
     */
    @Nullable
    public static BlockPos findNearestBell(ServerLevel level, BlockPos center, int radius) {
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        int yLimit = Math.min(radius, 16);

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -yLimit, -radius),
                center.offset(radius, yLimit, radius))) {
            if (level.getBlockState(pos).is(Blocks.BELL)) {
                double dist = center.distSqr(pos);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = pos.immutable();
                }
            }
        }
        return closest;
    }
}
