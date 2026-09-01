package com.orangevillager61.emeraldcapitalism.block;

import com.mojang.serialization.MapCodec;
import com.orangevillager61.emeraldcapitalism.util.DoorPairingUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class RegularEmeraldDoorBlock extends DoorBlock {

    public static final MapCodec<RegularEmeraldDoorBlock> CODEC = simpleCodec(RegularEmeraldDoorBlock::new);

    public RegularEmeraldDoorBlock(Properties properties) {
        super(BlockSetType.IRON, properties);
    }

    @Override
    public @NotNull MapCodec<? extends DoorBlock> codec() {
        return CODEC;
    }

    @Override
    public PathType getBlockPathType(BlockState state, BlockGetter level, BlockPos pos,
                                     @Nullable Mob mob) {
        return state.getValue(OPEN) ? PathType.DOOR_OPEN : PathType.DOOR_WOOD_CLOSED;
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level,
                                                         @NotNull BlockPos pos, @NotNull Player player,
                                                         @NotNull BlockHitResult hitResult) {
        BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        BlockState lowerState = level.getBlockState(lowerPos);
        if (!lowerState.is(this)) {
            return InteractionResult.PASS;
        }

        boolean open = !lowerState.getValue(OPEN);
        DoorPairingUtils.setDoorAndPairedOpen(level, lowerPos, open, Block.UPDATE_ALL);

        level.playSound(player, lowerPos, open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0F, 0.9F);
        return com.orangevillager61.emeraldcapitalism.util.InteractionResultCompat.sidedSuccess(level.isClientSide);
    }
}
