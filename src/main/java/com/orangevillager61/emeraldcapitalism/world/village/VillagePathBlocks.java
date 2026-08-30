package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import java.util.List;

/**
 * The surface and support blocks used by paths attached to a vanilla village.
 * Keeping this mapping in one place prevents generated paths from using a
 * material that does not belong to the village's palette.
 */
public record VillagePathBlocks(Block surfaceBlock, Block supportBlock, boolean preserveSand) {

    public static VillagePathBlocks forBiome(String biomeType) {
        return switch (biomeType == null ? "" : biomeType) {
            case "DESERT" -> new VillagePathBlocks(Blocks.SAND, Blocks.SANDSTONE, true);
            case "PLAINS", "SAVANNA", "TAIGA", "SNOWY" ->
                    new VillagePathBlocks(Blocks.DIRT_PATH, Blocks.COBBLESTONE, false);
            default -> new VillagePathBlocks(Blocks.DIRT_PATH, Blocks.COBBLESTONE, false);
        };
    }

    /**
     * Uses the surface block sampled from the connected village street so the
     * generated connector matches that street instead of guessing from biome.
     */
    public static VillagePathBlocks matchingStreet(Block streetSurface, String fallbackBiomeType) {
        if (streetSurface == Blocks.SAND) {
            return new VillagePathBlocks(Blocks.SAND, Blocks.SANDSTONE, true);
        }
        if (streetSurface == Blocks.RED_SAND) {
            return new VillagePathBlocks(Blocks.RED_SAND, Blocks.RED_SANDSTONE, true);
        }
        if (streetSurface == Blocks.GRAVEL) {
            return new VillagePathBlocks(Blocks.GRAVEL, Blocks.COBBLESTONE, false);
        }
        if (streetSurface == Blocks.COBBLESTONE || streetSurface == Blocks.MOSSY_COBBLESTONE) {
            return new VillagePathBlocks(streetSurface, Blocks.COBBLESTONE, false);
        }
        if (streetSurface == Blocks.DIRT_PATH) {
            return new VillagePathBlocks(Blocks.DIRT_PATH, Blocks.COBBLESTONE, false);
        }
        return forBiome(fallbackBiomeType);
    }

    /**
     * Infers the vanilla village palette from the road pool names. The biome
     * lookup is only a fallback for deferred placement, where the original
     * structure pieces may no longer be available.
     */
    public static String inferBiomeType(ServerLevel level, BlockPos fallbackPos,
                                        List<StructurePiece> pieces) {
        for (StructurePiece piece : pieces) {
            if (!(piece instanceof PoolElementStructurePiece poolPiece)) {
                continue;
            }
            String element = poolPiece.getElement().toString().toLowerCase(java.util.Locale.ROOT);
            if (element.contains("/desert/")) {
                return "DESERT";
            }
            if (element.contains("/savanna/")) {
                return "SAVANNA";
            }
            if (element.contains("/taiga/")) {
                return "TAIGA";
            }
            if (element.contains("/snowy/")) {
                return "SNOWY";
            }
            if (element.contains("/plains/")) {
                return "PLAINS";
            }
        }

        String biomePath = level.getBiome(fallbackPos).unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("")
                .toLowerCase(java.util.Locale.ROOT);
        if (biomePath.contains("desert")) {
            return "DESERT";
        }
        if (biomePath.contains("savanna")) {
            return "SAVANNA";
        }
        if (biomePath.contains("taiga")) {
            return "TAIGA";
        }
        if (biomePath.contains("snow") || biomePath.contains("ice")) {
            return "SNOWY";
        }
        return "PLAINS";
    }
}
