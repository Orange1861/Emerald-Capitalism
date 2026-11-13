package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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

    /** Persisted by the farm journal so an interrupted farm is replayed at the same site. */
    public static final Codec<FarmPlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("origin").forGetter(FarmPlacement::origin),
            ResourceLocation.CODEC.fieldOf("template").forGetter(FarmPlacement::templateLocation),
            ResourceLocation.CODEC.fieldOf("processors").forGetter(FarmPlacement::processorList),
            Rotation.CODEC.fieldOf("rotation").forGetter(FarmPlacement::rotation),
            Codec.intRange(1, 256).fieldOf("footprint_x").forGetter(FarmPlacement::footprintX),
            Codec.intRange(1, 256).fieldOf("footprint_z").forGetter(FarmPlacement::footprintZ)
    ).apply(instance, FarmPlacement::new));
}
