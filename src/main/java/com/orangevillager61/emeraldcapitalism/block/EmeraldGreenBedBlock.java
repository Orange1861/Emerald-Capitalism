package com.orangevillager61.emeraldcapitalism.block;

import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldGreenBedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public class EmeraldGreenBedBlock extends BedBlock {

    public EmeraldGreenBedBlock(BlockBehaviour.Properties properties) {
        super(DyeColor.GREEN, properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EmeraldGreenBedBlockEntity(pos, state);
    }
}
