package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A copy of the terrain the village planners read, so planning can run on the
 * planning worker without touching the live level.
 *
 * <p>Each column keeps both planning heightmaps, the block at the
 * {@code WORLD_SURFACE} height, and a window of block states from
 * {@link #DEPTH_BELOW} below to {@link #HEIGHT_ABOVE} above its
 * {@code MOTION_BLOCKING_NO_LEAVES} surface. Above the window nothing blocks
 * motion and no fluid exists, which the heightmap guarantees, so reads there
 * return air; reads below it return stone.</p>
 *
 * <p>The server thread captures and refreshes the copy; the worker only reads
 * it, and only while no capture or refresh is in progress. The pipeline task
 * that owns it orders those phases, and submitting to the worker publishes the
 * captured arrays. The copy is dropped with its owning task.</p>
 */
public final class VillageTerrainSnapshot implements VillageTerrain {
    static final int DEPTH_BELOW = 10;
    static final int HEIGHT_ABOVE = 4;
    private static final int WINDOW = DEPTH_BELOW + HEIGHT_ABOVE + 1;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState BELOW_WINDOW = Blocks.STONE.defaultBlockState();

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final int minBuildHeight;
    private final boolean[] chunkCaptured;
    private final int[] noLeavesHeight;
    private final int[] worldSurfaceHeight;
    private final BlockState[] worldSurfaceState;
    private final BlockState[] window;
    private int nextChunk;

    public VillageTerrainSnapshot(ServerLevel level, int minChunkX, int maxChunkX,
                                  int minChunkZ, int maxChunkZ) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = maxChunkX - minChunkX + 1;
        this.chunksZ = maxChunkZ - minChunkZ + 1;
        this.minX = minChunkX << 4;
        this.minZ = minChunkZ << 4;
        this.sizeX = chunksX << 4;
        this.sizeZ = chunksZ << 4;
        this.minBuildHeight = com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.min(level);
        int columns = sizeX * sizeZ;
        this.chunkCaptured = new boolean[chunksX * chunksZ];
        this.noLeavesHeight = new int[columns];
        this.worldSurfaceHeight = new int[columns];
        this.worldSurfaceState = new BlockState[columns];
        this.window = new BlockState[columns * WINDOW];
    }

    /** Captures up to {@code chunksPerStep} more chunks; returns true once every chunk is done. */
    public boolean captureStep(ServerLevel level, int chunksPerStep) {
        int end = Math.min(chunkCaptured.length, nextChunk + chunksPerStep);
        for (; nextChunk < end; nextChunk++) {
            int chunkX = minChunkX + nextChunk / chunksZ;
            int chunkZ = minChunkZ + nextChunk % chunksZ;
            captureChunk(level, chunkX, chunkZ);
        }
        return nextChunk >= chunkCaptured.length;
    }

    /** Starts a full recapture, used after building placement has changed the terrain. */
    public void restartCapture() {
        nextChunk = 0;
    }

    /** Re-reads one column after the server thread changed it. */
    public void refreshColumn(ServerLevel level, int x, int z) {
        int column = columnIndex(x, z);
        if (column < 0 || !level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        captureColumn(level, column, x, z, new BlockPos.MutableBlockPos());
    }

    private void captureChunk(ServerLevel level, int chunkX, int chunkZ) {
        int chunkIndex = (chunkX - minChunkX) * chunksZ + (chunkZ - minChunkZ);
        if (!level.hasChunk(chunkX, chunkZ)) {
            chunkCaptured[chunkIndex] = false;
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
            for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                captureColumn(level, columnIndex(x, z), x, z, cursor);
            }
        }
        chunkCaptured[chunkIndex] = true;
    }

    private void captureColumn(ServerLevel level, int column, int x, int z, BlockPos.MutableBlockPos cursor) {
        int noLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int worldSurface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        noLeavesHeight[column] = noLeaves;
        worldSurfaceHeight[column] = worldSurface;
        worldSurfaceState[column] = level.getBlockState(cursor.set(x, worldSurface - 1, z));
        int base = noLeaves - 1 - DEPTH_BELOW;
        for (int offset = 0; offset < WINDOW; offset++) {
            window[column * WINDOW + offset] = level.getBlockState(cursor.set(x, base + offset, z));
        }
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        return localX >= 0 && localX < chunksX && localZ >= 0 && localZ < chunksZ
                && chunkCaptured[localX * chunksZ + localZ];
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        int column = columnIndex(x, z);
        if (column < 0) {
            return minBuildHeight;
        }
        return switch (type) {
            case MOTION_BLOCKING_NO_LEAVES -> noLeavesHeight[column];
            case WORLD_SURFACE -> worldSurfaceHeight[column];
            default -> throw new IllegalArgumentException("Heightmap not captured: " + type);
        };
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int column = columnIndex(pos.getX(), pos.getZ());
        if (column < 0 || !hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return AIR;
        }
        int offset = pos.getY() - (noLeavesHeight[column] - 1 - DEPTH_BELOW);
        if (offset >= 0 && offset < WINDOW) {
            return window[column * WINDOW + offset];
        }
        if (pos.getY() == worldSurfaceHeight[column] - 1) {
            return worldSurfaceState[column];
        }
        return offset >= WINDOW ? AIR : BELOW_WINDOW;
    }

    @Override
    public boolean hasCollision(BlockPos pos) {
        return !getBlockState(pos).getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty();
    }

    @Override
    public int minBuildHeight() {
        return minBuildHeight;
    }

    private int columnIndex(int x, int z) {
        int localX = x - minX;
        int localZ = z - minZ;
        if (localX < 0 || localX >= sizeX || localZ < 0 || localZ >= sizeZ) {
            return -1;
        }
        return localX * sizeZ + localZ;
    }
}
