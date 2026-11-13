package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The surface and support blocks used by paths attached to a vanilla village.
 * Keeping this mapping in one place prevents generated paths from using a
 * material that does not belong to the village's palette.
 *
 * <p>{@code stairBlock} and {@code slabBlock} soften one-block height steps;
 * a palette without them leaves those steps as plain path blocks.
 * {@code fillBlock} goes under stairs and slabs and into graded ramps.</p>
 */
public record VillagePathBlocks(Block surfaceBlock, Block supportBlock, boolean preserveSand,
                                Block fillBlock, @Nullable Block stairBlock,
                                @Nullable Block slabBlock) {

    public static VillagePathBlocks forBiome(String biomeType) {
        return switch (biomeType == null ? "" : biomeType) {
            case "DESERT" -> desert();
            default -> dirtPath();
        };
    }

    /**
     * Uses the surface block sampled from the connected village street so the
     * generated connector matches that street instead of guessing from biome.
     */
    public static VillagePathBlocks matchingStreet(Block streetSurface, String fallbackBiomeType) {
        if (streetSurface == Blocks.SMOOTH_SANDSTONE || streetSurface == Blocks.SAND) {
            // Desert streets are smooth sandstone; a street piece also covers plain
            // sand beside it, which must not turn the connector into sand.
            return desert();
        }
        if (streetSurface == Blocks.RED_SAND) {
            return new VillagePathBlocks(Blocks.RED_SAND, Blocks.RED_SANDSTONE, true,
                    Blocks.RED_SANDSTONE, Blocks.RED_SANDSTONE_STAIRS, Blocks.RED_SANDSTONE_SLAB);
        }
        if (streetSurface == Blocks.GRAVEL) {
            return new VillagePathBlocks(Blocks.GRAVEL, Blocks.COBBLESTONE, false,
                    Blocks.COBBLESTONE, null, null);
        }
        if (streetSurface == Blocks.MOSSY_COBBLESTONE) {
            return new VillagePathBlocks(streetSurface, Blocks.COBBLESTONE, false,
                    Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_SLAB);
        }
        if (streetSurface == Blocks.COBBLESTONE) {
            return new VillagePathBlocks(streetSurface, Blocks.COBBLESTONE, false,
                    Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_SLAB);
        }
        if (streetSurface == Blocks.DIRT_PATH) {
            return dirtPath();
        }
        return forBiome(fallbackBiomeType);
    }

    /** Whether a block is a stair or slab that a generated connector path places. */
    public static boolean isPathStep(Block block) {
        return block == ECAPBlocks.DIRT_PATH_STAIRS.get() || block == ECAPBlocks.DIRT_PATH_SLAB.get()
                || block == Blocks.SANDSTONE_STAIRS || block == Blocks.SANDSTONE_SLAB
                || block == Blocks.SMOOTH_SANDSTONE_STAIRS || block == Blocks.SMOOTH_SANDSTONE_SLAB
                || block == Blocks.RED_SANDSTONE_STAIRS || block == Blocks.RED_SANDSTONE_SLAB
                || block == Blocks.COBBLESTONE_STAIRS || block == Blocks.COBBLESTONE_SLAB
                || block == Blocks.MOSSY_COBBLESTONE_STAIRS || block == Blocks.MOSSY_COBBLESTONE_SLAB;
    }

    private static VillagePathBlocks dirtPath() {
        return new VillagePathBlocks(Blocks.DIRT_PATH, Blocks.COBBLESTONE, false, Blocks.DIRT,
                ECAPBlocks.DIRT_PATH_STAIRS.get(), ECAPBlocks.DIRT_PATH_SLAB.get());
    }

    /** Desert connectors are paved like desert village streets, never with sand. */
    private static VillagePathBlocks desert() {
        return new VillagePathBlocks(Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE, false,
                Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB);
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
