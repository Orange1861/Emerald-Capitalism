package com.orangevillager61.emeraldcapitalism.block;

import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.function.Supplier;

public class EmeraldChestBlock extends ChestBlock {

    public static final MapCodec<EmeraldChestBlock> CODEC = simpleCodec(
            props -> new EmeraldChestBlock(props, () -> ECAPBlockEntityTypes.EMERALD_CHEST.get())
    );

    public EmeraldChestBlock(Properties properties, Supplier<BlockEntityType<? extends ChestBlockEntity>> blockEntityType) {
        super(properties, blockEntityType);
    }

    @Override
    public @NotNull MapCodec<? extends ChestBlock> codec() {
        return CODEC;
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new EmeraldChestBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        return level.isClientSide
                ? createTickerHelper(type, ECAPBlockEntityTypes.EMERALD_CHEST.get(), ChestBlockEntity::lidAnimateTick)
                : null;
    }
}
