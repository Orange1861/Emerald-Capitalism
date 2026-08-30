package com.orangevillager61.emeraldcapitalism.world.structure;

import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Server-side locate helpers for the mod's abandoned-vault structure set. */
public final class AbandonedVaultLocator {
    public static final ResourceKey<Structure> STRUCTURE_KEY = ResourceKey.create(
            Registries.STRUCTURE, ModIds.id("bank_vault_ruins"));
    public static final int SEARCH_RADIUS_CHUNKS = 100;

    private AbandonedVaultLocator() {
    }

    public static Optional<BlockPos> findNearest(ServerLevel level, BlockPos origin) {
        return findDistinct(level, origin).stream().findFirst();
    }

    /** Returns the second distinct generated vault, never the vault at the source position. */
    public static Optional<BlockPos> findSecondNearest(ServerLevel level, BlockPos origin) {
        List<BlockPos> found = findDistinct(level, origin);
        return found.size() < 2 ? Optional.empty() : Optional.of(found.get(1));
    }

    /** Returns the nearest distinct structure positions in distance order. */
    public static List<BlockPos> findDistinct(ServerLevel level, BlockPos origin) {
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Optional<Holder.Reference<Structure>> holder = structures.getHolder(STRUCTURE_KEY);
        if (holder.isEmpty()) {
            return List.of();
        }

        ChunkGeneratorStructureState generatorState = level.getChunkSource().getGeneratorState();
        List<StructurePlacement> placements = generatorState.getPlacementsForStructure(holder.get());
        Map<Long, BlockPos> located = new HashMap<>();
        for (BlockPos position : VillageRegistryData.get(level).getAbandonedVaultPositions()) {
            if (isWithinSearchRadius(origin, position)) {
                located.put(position.asLong(), position.immutable());
            }
        }
        for (StructurePlacement placement : placements) {
            if (!(placement instanceof RandomSpreadStructurePlacement randomSpread)) {
                continue;
            }
            searchRandomSpread(level, origin, holder.get(), randomSpread, located);
        }

        return located.values().stream()
                .sorted(Comparator.comparingDouble(position -> horizontalDistanceSqr(position, origin)))
                .toList();
    }

    private static void searchRandomSpread(ServerLevel level, BlockPos origin,
                                           Holder<Structure> structure,
                                           RandomSpreadStructurePlacement placement,
                                           Map<Long, BlockPos> located) {
        long seed = level.getChunkSource().getGeneratorState().getLevelSeed();
        int sourceChunkX = SectionPos.blockToSectionCoord(origin.getX());
        int sourceChunkZ = SectionPos.blockToSectionCoord(origin.getZ());

        int cellRadius = Math.ceilDiv(SEARCH_RADIUS_CHUNKS, placement.spacing()) + 1;
        for (int radius = 0; radius <= cellRadius; radius++) {
            for (int gridX = -radius; gridX <= radius; gridX++) {
                for (int gridZ = -radius; gridZ <= radius; gridZ++) {
                    if (radius > 0 && Math.abs(gridX) != radius && Math.abs(gridZ) != radius) {
                        continue;
                    }
                    ChunkPos candidate = placement.getPotentialStructureChunk(
                            seed, Math.floorDiv(sourceChunkX, placement.spacing()) + gridX,
                            Math.floorDiv(sourceChunkZ, placement.spacing()) + gridZ);
                    if (Math.max(Math.abs(candidate.x - sourceChunkX),
                            Math.abs(candidate.z - sourceChunkZ)) > SEARCH_RADIUS_CHUNKS) {
                        continue;
                    }
                    if (located.containsKey(candidate.toLong())) {
                        continue;
                    }
                    BlockPos result = loadGeneratedStructure(level, structure, placement, candidate);
                    if (result != null && isWithinSearchRadius(origin, result)) {
                        located.put(candidate.toLong(), result);
                    }
                }
            }

            if (located.size() >= 2) {
                double secondDistance = located.values().stream()
                        .mapToDouble(vault -> horizontalDistanceSqr(vault, origin))
                        .sorted()
                        .skip(1)
                        .findFirst()
                        .orElse(Double.MAX_VALUE);
                // A random-spread cell outside the next square cannot be closer
                // than this conservative chunk-distance lower bound.
                double nextSquareLowerBound = Math.max(0,
                        (radius + 1) * placement.spacing() - placement.spacing() - 2) * 16.0D;
                if (nextSquareLowerBound * nextSquareLowerBound > secondDistance) {
                    return;
                }
            }
        }
    }

    @Nullable
    private static BlockPos loadGeneratedStructure(ServerLevel level,
                                                   Holder<Structure> structure,
                                                   StructurePlacement placement,
                                                   ChunkPos candidate) {
        ChunkAccess chunk = level.getChunk(candidate.x, candidate.z, ChunkStatus.STRUCTURE_STARTS);
        StructureManager manager = level.structureManager();
        StructureStart start = manager.getStartForStructure(
                SectionPos.bottomOf(chunk), structure.value(), chunk);
        if (start == null || !start.isValid()) {
            return null;
        }
        BlockPos locatePosition = placement.getLocatePos(start.getChunkPos()).immutable();
        return locatePosition;
    }

    private static boolean isWithinSearchRadius(BlockPos origin, BlockPos position) {
        int maxDistance = SEARCH_RADIUS_CHUNKS * 16;
        return Math.max(Math.abs(position.getX() - origin.getX()),
                Math.abs(position.getZ() - origin.getZ())) <= maxDistance;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        long deltaX = (long) first.getX() - second.getX();
        long deltaZ = (long) first.getZ() - second.getZ();
        return (double) deltaX * deltaX + (double) deltaZ * deltaZ;
    }
}
