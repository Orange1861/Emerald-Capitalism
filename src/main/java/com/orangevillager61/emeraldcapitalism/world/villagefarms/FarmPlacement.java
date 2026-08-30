package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

/**
 * Describes a single farm to be placed in the world.
 *
 * @param origin           the block position where the structure will be placed
 * @param templateLocation the resource location of the farm NBT template
 * @param processorList    the resource location of the processor list to apply
 * @param rotation         the rotation to apply when placing the structure
 * @param footprintX       the X size of the farm footprint after rotation
 * @param footprintZ       the Z size of the farm footprint after rotation
 */
public record FarmPlacement(
        BlockPos origin,
        ResourceLocation templateLocation,
        ResourceLocation processorList,
        Rotation rotation,
        int footprintX,
        int footprintZ
) {
}
