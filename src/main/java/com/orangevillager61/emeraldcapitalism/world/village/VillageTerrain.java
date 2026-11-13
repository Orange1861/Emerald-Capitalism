package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The world reads made by village site and connector planning. Planning code
 * reads only through this boundary, so it can run on the server thread against
 * the live level or on the planning worker against a {@link VillageTerrainSnapshot}.
 * Implementations never load chunks.
 */
public interface VillageTerrain {
    boolean hasChunk(int chunkX, int chunkZ);

    /**
     * The top non-air column height for {@code MOTION_BLOCKING_NO_LEAVES} or
     * {@code WORLD_SURFACE}, as {@link ServerLevel#getHeight} reports it. Callers
     * check {@link #hasChunk} first.
     */
    int getHeight(Heightmap.Types type, int x, int z);

    BlockState getBlockState(BlockPos pos);

    /** Whether the block at {@code pos} has any collision shape. */
    boolean hasCollision(BlockPos pos);

    int minBuildHeight();

    static VillageTerrain live(ServerLevel level) {
        return new VillageTerrain() {
            @Override
            public boolean hasChunk(int chunkX, int chunkZ) {
                return level.hasChunk(chunkX, chunkZ);
            }

            @Override
            public int getHeight(Heightmap.Types type, int x, int z) {
                return level.getHeight(type, x, z);
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return level.getBlockState(pos);
            }

            @Override
            public boolean hasCollision(BlockPos pos) {
                return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            }

            @Override
            public int minBuildHeight() {
                return com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.min(level);
            }
        };
    }
}
