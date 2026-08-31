package com.orangevillager61.emeraldcapitalism.block.entity;

import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class EmeraldGreenBedBlockEntity extends BlockEntity {

    public EmeraldGreenBedBlockEntity(BlockPos pos, BlockState state) {
        super(ECAPBlockEntityTypes.EMERALD_GREEN_BED.get(), pos, state);
    }

    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public DyeColor getColor() {
        return DyeColor.GREEN;
    }
}
