package com.orangevillager61.emeraldcapitalism.util;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A short-lived view of loaded chunks whose sections may contain a block type.
 *
 * <p>The section test is deliberately conservative: a positive result only
 * means that a section might contain a matching block. A negative result is
 * safe because {@link LevelChunkSection#maybeHas(Predicate)} is based on the
 * section's composition data. The view also reads states directly from the
 * already loaded chunks, so an AI search does not synchronously load a full
 * chunk for every block position.</p>
 */
public final class LoadedChunkComposition {

    private final Map<Long, LevelChunk> loadedChunks;
    private final LongSet matchingSections;

    private LoadedChunkComposition(Map<Long, LevelChunk> loadedChunks, LongSet matchingSections) {
        this.loadedChunks = loadedChunks;
        this.matchingSections = matchingSections;
    }

    public static LoadedChunkComposition find(ServerLevel level,
                                              int minX, int maxX,
                                              int minY, int maxY,
                                              int minZ, int maxZ,
                                              Predicate<BlockState> predicate) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(predicate, "predicate");
        Map<Long, LevelChunk> loadedChunks = new HashMap<>();
        LongSet matchingSections = new LongOpenHashSet();
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return new LoadedChunkComposition(loadedChunks, matchingSections);
        }

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        int minSectionY = SectionPos.blockToSectionCoord(minY);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                loadedChunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
                LevelChunkSection[] sections = chunk.getSections();
                int chunkMinSectionY = SectionPos.blockToSectionCoord(
                        com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.min(chunk));
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    int sectionY = chunkMinSectionY + sectionIndex;
                    if (sectionY >= minSectionY && sectionY <= maxSectionY
                            && sections[sectionIndex].maybeHas(predicate)) {
                        matchingSections.add(SectionPos.asLong(chunkX, sectionY, chunkZ));
                    }
                }
            }
        }
        return new LoadedChunkComposition(loadedChunks, matchingSections);
    }

    public boolean mayContain(BlockPos pos) {
        return matchingSections.contains(SectionPos.asLong(pos));
    }

    public boolean isEmpty() {
        return matchingSections.isEmpty();
    }

    /** Returns a state only when the candidate's chunk was loaded for this view. */
    public BlockState getBlockStateIfLoaded(BlockPos pos) {
        LevelChunk chunk = loadedChunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        return chunk == null ? null : chunk.getBlockState(pos);
    }
}
