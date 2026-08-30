package com.orangevillager61.emeraldcapitalism.world.structure;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

/** Deterministically resolves the one Steve grave target for a world seed. */
public final class SteveGraveTargetPlanner {
    public static final int MIN_AXIS_DISTANCE = 10_000;

    private static final long CANDIDATE_SALT = 0x6A09E667F3BCC909L;
    private static final int CANDIDATE_MIN_DISTANCE = 16_384;
    private static final int CANDIDATE_DISTANCE_RANGE = 48_000;
    private static final int MAX_CANDIDATES = 256;
    // Keep the fallback noise probe bounded. Candidate points are already
    // spread through the valid annulus, so this only needs to catch a nearby
    // Frozen Peaks region instead of scanning a large volume repeatedly.
    private static final int BIOME_SEARCH_RADIUS = 1_024;
    private static final int BIOME_SEARCH_HORIZONTAL_STEP = 64;
    private static final int BIOME_SEARCH_VERTICAL_STEP = 32;
    private static final int FOOTPRINT_SAMPLE_STEP = 8;

    private SteveGraveTargetPlanner() {
    }

    /**
     * Finds the first valid candidate in a stable seed-derived sequence.
     * Biome and terrain queries use the generator's noise source; this method
     * does not load chunks.
     */
    public static Optional<BlockPos> findTarget(ServerLevel level, BlockPos spawn,
                                                StructureTemplate template) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        BiomeSource biomeSource = generator.getBiomeSource();

        for (int attempt = 0; attempt < MAX_CANDIDATES; attempt++) {
            long candidateSeed = mix64(level.getSeed() ^ CANDIDATE_SALT
                    ^ ((long) attempt * 0x9E3779B97F4A7C15L));
            int distanceX = CANDIDATE_MIN_DISTANCE
                    + bounded(candidateSeed, CANDIDATE_DISTANCE_RANGE);
            int distanceZ = CANDIDATE_MIN_DISTANCE
                    + bounded(Long.rotateLeft(candidateSeed, 31), CANDIDATE_DISTANCE_RANGE);
            int x = signedOffset(spawn.getX(), distanceX, (candidateSeed & 1L) == 0L);
            int z = signedOffset(spawn.getZ(), distanceZ, (candidateSeed & 2L) == 0L);

            Pair<BlockPos, Holder<Biome>> match = level.findClosestBiome3d(
                    holder -> holder.is(Biomes.FROZEN_PEAKS),
                    new BlockPos(x, level.getSeaLevel(), z),
                    BIOME_SEARCH_RADIUS,
                    BIOME_SEARCH_HORIZONTAL_STEP,
                    BIOME_SEARCH_VERTICAL_STEP
            );
            if (match == null) {
                continue;
            }

            BlockPos matchPosition = match.getFirst();
            BoundingBox footprint = footprint(template, matchPosition.getX(), matchPosition.getZ());
            if (!satisfiesAxisDistance(spawn, footprint)) {
                continue;
            }
            if (!isFrozenPeaksFootprint(level, biomeSource, randomState, footprint)) {
                continue;
            }
            return Optional.of(new BlockPos(matchPosition.getX(), 0, matchPosition.getZ()));
        }

        return Optional.empty();
    }

    /** Package-visible for focused unit tests and to keep the distance rule explicit. */
    static boolean satisfiesAxisDistance(BlockPos spawn, BoundingBox footprint) {
        return distanceToInterval(spawn.getX(), footprint.minX(), footprint.maxX()) >= MIN_AXIS_DISTANCE
                && distanceToInterval(spawn.getZ(), footprint.minZ(), footprint.maxZ()) >= MIN_AXIS_DISTANCE;
    }

    static BoundingBox footprint(StructureTemplate template, int originX, int originZ) {
        StructurePlaceSettings settings = new StructurePlaceSettings();
        return template.getBoundingBox(settings, new BlockPos(originX, 0, originZ));
    }

    private static boolean isFrozenPeaksFootprint(ServerLevel level, BiomeSource biomeSource,
                                                  RandomState randomState, BoundingBox footprint) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (int x = footprint.minX(); x <= footprint.maxX(); x += FOOTPRINT_SAMPLE_STEP) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z += FOOTPRINT_SAMPLE_STEP) {
                if (!isFrozenPeaksAtSurface(level, generator, biomeSource, randomState, x, z)) {
                    return false;
                }
            }
        }

        // Always include the far edges when the dimensions are not multiples of
        // the sample step.
        return isFrozenPeaksAtSurface(level, generator, biomeSource, randomState,
                footprint.maxX(), footprint.minZ())
                && isFrozenPeaksAtSurface(level, generator, biomeSource, randomState,
                footprint.minX(), footprint.maxZ())
                && isFrozenPeaksAtSurface(level, generator, biomeSource, randomState,
                footprint.maxX(), footprint.maxZ());
    }

    private static boolean isFrozenPeaksAtSurface(ServerLevel level, ChunkGenerator generator,
                                                 BiomeSource biomeSource, RandomState randomState,
                                                 int x, int z) {
        int surfaceY = generator.getFirstOccupiedHeight(
                x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        Holder<Biome> biome = biomeSource.getNoiseBiome(
                QuartPos.fromBlock(x),
                QuartPos.fromBlock(surfaceY),
                QuartPos.fromBlock(z),
                randomState.sampler()
        );
        return biome.is(Biomes.FROZEN_PEAKS);
    }

    private static long distanceToInterval(int origin, int min, int max) {
        if (origin < min) {
            return (long) min - origin;
        }
        if (origin > max) {
            return (long) origin - max;
        }
        return 0L;
    }

    private static int signedOffset(int origin, int distance, boolean positive) {
        return positive ? origin + distance : origin - distance;
    }

    private static int bounded(long value, int bound) {
        return (int) Long.remainderUnsigned(value, bound);
    }

    /** Stable SplitMix64 finalizer; avoids depending on Minecraft RNG changes. */
    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
