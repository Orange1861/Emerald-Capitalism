package com.orangevillager61.emeraldcapitalism.block;

import com.mojang.serialization.MapCodec;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.DoorPairingUtils;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
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

public class EmeraldDoorBlock extends DoorBlock {

    public static final MapCodec<EmeraldDoorBlock> CODEC = simpleCodec(EmeraldDoorBlock::new);

    public EmeraldDoorBlock(Properties properties) {
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

    @Nullable
    @Override
    public BlockState getStateForPlacement(@NotNull BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        if (pos.getY() >= com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.max(context.getLevel()) - 2) {
            return null;
        }
        if (!context.getLevel().getBlockState(pos.above(2)).canBeReplaced(context)) {
            return null;
        }
        return super.getStateForPlacement(context);
    }

    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                            @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        BlockPos topPos = pos.above(2);
        BlockState topState = ECAPBlocks.EMERALD_DOOR_TOP.get().defaultBlockState()
                .setValue(EmeraldDoorTopBlock.FACING, state.getValue(FACING))
                .setValue(EmeraldDoorTopBlock.OPEN, state.getValue(OPEN))
                .setValue(EmeraldDoorTopBlock.HINGE, state.getValue(HINGE));

        level.setBlock(topPos, topState, Block.UPDATE_ALL);
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
        if (open && level instanceof ServerLevel serverLevel) {
            VillageGovernance.endGovernorCandidateAttackGrace(serverLevel, lowerPos, player.getUUID());
        }
        level.playSound(player, lowerPos, open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0F, 0.9F);
        return com.orangevillager61.emeraldcapitalism.util.InteractionResultCompat.sidedSuccess(level.isClientSide);
    }

    public static void syncTopState(@NotNull Level level, @NotNull BlockPos lowerDoorPos) {
        BlockState lowerState = level.getBlockState(lowerDoorPos);
        if (!lowerState.is(ECAPBlocks.EMERALD_DOOR.get())) {
            return;
        }

        BlockPos topPos = lowerDoorPos.above(2);
        BlockState topState = level.getBlockState(topPos);
        if (!topState.is(ECAPBlocks.EMERALD_DOOR_TOP.get())) {
            return;
        }

        BlockState syncedTop = topState
                .setValue(EmeraldDoorTopBlock.FACING, lowerState.getValue(FACING))
                .setValue(EmeraldDoorTopBlock.OPEN, lowerState.getValue(OPEN))
                .setValue(EmeraldDoorTopBlock.HINGE, lowerState.getValue(HINGE));

        // Use full block updates so lighting/neighbor updates are refreshed when the
        // third segment changes (open/close, hinge, facing), avoiding stale light data.
        level.setBlock(topPos, syncedTop, Block.UPDATE_ALL);
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                         @NotNull BlockState newState, boolean movedByPiston) {
        if (!newState.is(this)) {
            BlockPos topPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above(2) : pos.above();
            if (level.getBlockState(topPos).is(ECAPBlocks.EMERALD_DOOR_TOP.get())) {
                level.destroyBlock(topPos, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
