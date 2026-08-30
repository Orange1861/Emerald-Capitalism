package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.block.EmeraldDoorBlock;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Optional;

public final class DoorPairingUtils {

    private DoorPairingUtils() {}

    public static boolean isEmeraldDoorFamily(@NotNull BlockState state) {
        return state.is(ECAPBlocks.EMERALD_DOOR.get()) || state.is(ECAPBlocks.REGULAR_EMERALD_DOOR.get());
    }

    public static @Nullable BlockPos lowerDoorPos(@NotNull BlockPos pos, @NotNull BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock) || !isEmeraldDoorFamily(state)) {
            return null;
        }
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
    }

    public static Optional<BlockPos> findPairedLowerDoor(@NotNull Level level, @NotNull BlockPos lowerPos) {
        BlockState baseState = level.getBlockState(lowerPos);
        if (!(baseState.getBlock() instanceof DoorBlock)
                || !isEmeraldDoorFamily(baseState)
                || baseState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return Optional.empty();
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = lowerPos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (!(neighborState.getBlock() instanceof DoorBlock)
                    || !neighborState.is(baseState.getBlock())
                    || neighborState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                continue;
            }
            if (neighborState.getValue(DoorBlock.FACING) != baseState.getValue(DoorBlock.FACING)) {
                continue;
            }
            if (neighborState.getValue(DoorBlock.HINGE) == baseState.getValue(DoorBlock.HINGE)) {
                continue;
            }

            if (baseState.is(ECAPBlocks.EMERALD_DOOR.get())
                    && !level.getBlockState(neighborPos.above(2)).is(ECAPBlocks.EMERALD_DOOR_TOP.get())) {
                continue;
            }

            return Optional.of(neighborPos);
        }

        return Optional.empty();
    }

    public static void setDoorOpen(@NotNull Level level, @NotNull BlockPos lowerPos, boolean open, int flags) {
        BlockState lowerState = level.getBlockState(lowerPos);
        if (!(lowerState.getBlock() instanceof DoorBlock)
                || !isEmeraldDoorFamily(lowerState)
                || lowerState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }

        if (lowerState.getValue(BlockStateProperties.OPEN) != open) {
            level.setBlock(lowerPos, lowerState.setValue(BlockStateProperties.OPEN, open), flags);
        }

        BlockPos upperPos = lowerPos.above();
        BlockState upperState = level.getBlockState(upperPos);
        if (upperState.getBlock() == lowerState.getBlock()
                && upperState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                && upperState.getValue(BlockStateProperties.OPEN) != open) {
            level.setBlock(upperPos, upperState.setValue(BlockStateProperties.OPEN, open), flags);
        }

        if (lowerState.is(ECAPBlocks.EMERALD_DOOR.get())) {
            EmeraldDoorBlock.syncTopState(level, lowerPos);
        }
    }

    public static void setDoorAndPairedOpen(@NotNull Level level, @NotNull BlockPos lowerPos, boolean open, int flags) {
        setDoorOpen(level, lowerPos, open, flags);
        findPairedLowerDoor(level, lowerPos).ifPresent(pair -> setDoorOpen(level, pair, open, flags));
    }
}
