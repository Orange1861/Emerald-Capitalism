package com.orangevillager61.emeraldcapitalism.world.structure;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

/** Places the persisted Steve grave template once all affected chunks are ready. */
public final class SteveGravePlacer {
    public static final net.minecraft.resources.ResourceLocation TEMPLATE_ID = ModIds.id("steve_grave");

    public enum AttemptStatus {
        NOT_READY,
        PLACED,
        FAILED
    }

    public record PlacementAttempt(AttemptStatus status, BlockPos origin) {
        private static PlacementAttempt notReady() {
            return new PlacementAttempt(AttemptStatus.NOT_READY, null);
        }

        private static PlacementAttempt placed(BlockPos origin) {
            return new PlacementAttempt(AttemptStatus.PLACED, origin);
        }

        private static PlacementAttempt failed() {
            return new PlacementAttempt(AttemptStatus.FAILED, null);
        }
    }

    private SteveGravePlacer() {
    }

    /** Attempts placement without loading chunks; the result distinguishes waiting from failure. */
    public static PlacementAttempt placeIfReady(ServerLevel level, BlockPos target) {
        Optional<StructureTemplate> templateOptional = level.getStructureManager().get(TEMPLATE_ID);
        if (templateOptional.isEmpty()) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Cannot place Steve grave: missing template {}", TEMPLATE_ID);
            return PlacementAttempt.failed();
        }

        StructureTemplate template = templateOptional.get();
        StructurePlaceSettings settings = placementSettings();
        BoundingBox horizontalFootprint = SteveGraveTargetPlanner.footprint(
                template, target.getX(), target.getZ());
        if (!allChunksLoaded(level, horizontalFootprint)) {
            return PlacementAttempt.notReady();
        }

        int surfaceY = level.getHeight(
                Heightmap.Types.WORLD_SURFACE, target.getX(), target.getZ()) - 1;
        BlockPos origin = new BlockPos(target.getX(), surfaceY - 1, target.getZ());
        BoundingBox placedBox = template.getBoundingBox(settings, origin);
        if (origin.getY() < level.getMinBuildHeight()
                || placedBox.maxY() >= level.getMaxBuildHeight()
                || !allChunksLoaded(level, placedBox)) {
            return placedBox.maxY() >= level.getMaxBuildHeight()
                    || origin.getY() < level.getMinBuildHeight()
                    ? PlacementAttempt.failed() : PlacementAttempt.notReady();
        }

        if (!isFrozenPeaksAtPlacement(level, placedBox)) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Refusing Steve grave placement outside Frozen Peaks at {}",
                    origin);
            return PlacementAttempt.failed();
        }

        boolean placed = template.placeInWorld(
                level,
                origin,
                origin,
                settings,
                RandomSource.create(level.getSeed() ^ origin.asLong()),
                2
        );
        if (!placed) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Steve grave template refused placement at {}", origin);
            return PlacementAttempt.failed();
        }

        EmeraldCapitalism.LOGGER.info(
                "[ECAP] Placed Steve grave at {}", origin);
        return PlacementAttempt.placed(origin);
    }

    public static BoundingBox footprint(StructureTemplate template, BlockPos origin) {
        return template.getBoundingBox(placementSettings(), origin);
    }

    private static StructurePlaceSettings placementSettings() {
        return new StructurePlaceSettings()
                .setRotation(Rotation.NONE)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false);
    }

    private static boolean allChunksLoaded(ServerLevel level, BoundingBox box) {
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isFrozenPeaksAtPlacement(ServerLevel level, BoundingBox box) {
        for (int x = box.minX(); x <= box.maxX(); x += 8) {
            for (int z = box.minZ(); z <= box.maxZ(); z += 8) {
                if (!level.getBiome(new BlockPos(x, box.minY(), z)).is(Biomes.FROZEN_PEAKS)) {
                    return false;
                }
            }
        }
        return level.getBiome(new BlockPos(box.maxX(), box.minY(), box.maxZ()))
                .is(Biomes.FROZEN_PEAKS);
    }
}
