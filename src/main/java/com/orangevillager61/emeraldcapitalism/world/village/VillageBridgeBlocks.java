package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/** Deck, post and railing blocks for the small bridges on village connector paths. */
public record VillageBridgeBlocks(BlockState deck, BlockState post, BlockState rail) {

    /**
     * Plains villages whose lumbermill roll selects the second plains template
     * use birch to match it; every other plains village uses oak.
     */
    public static VillageBridgeBlocks forVillage(ServerLevel level, @Nullable BlockPos bellPos,
                                                 String biomeType) {
        if ("PLAINS".equals(biomeType) && bellPos != null
                && VillageLumbermillStructurePlacer.isPlainsVariantTwoSelected(level.getSeed(), bellPos)) {
            return new VillageBridgeBlocks(Blocks.BIRCH_PLANKS.defaultBlockState(),
                    Blocks.BIRCH_LOG.defaultBlockState(), Blocks.BIRCH_FENCE.defaultBlockState());
        }
        return forBiome(biomeType);
    }

    public static VillageBridgeBlocks forBiome(String biomeType) {
        return switch (biomeType == null ? "" : biomeType) {
            case "DESERT" -> new VillageBridgeBlocks(Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
                    Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.SANDSTONE_WALL.defaultBlockState());
            case "SAVANNA" -> new VillageBridgeBlocks(Blocks.ACACIA_PLANKS.defaultBlockState(),
                    Blocks.ACACIA_LOG.defaultBlockState(), Blocks.ACACIA_FENCE.defaultBlockState());
            case "TAIGA", "SNOWY" -> new VillageBridgeBlocks(Blocks.SPRUCE_PLANKS.defaultBlockState(),
                    Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_FENCE.defaultBlockState());
            default -> new VillageBridgeBlocks(Blocks.OAK_PLANKS.defaultBlockState(),
                    Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState());
        };
    }
}
