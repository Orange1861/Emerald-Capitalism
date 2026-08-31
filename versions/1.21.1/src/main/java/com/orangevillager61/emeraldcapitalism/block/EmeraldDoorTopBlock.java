package com.orangevillager61.emeraldcapitalism.block;

import com.mojang.serialization.MapCodec;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.DoorPairingUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class EmeraldDoorTopBlock extends Block {

    public static final MapCodec<EmeraldDoorTopBlock> CODEC = simpleCodec(EmeraldDoorTopBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final EnumProperty<DoorHingeSide> HINGE = BlockStateProperties.DOOR_HINGE;

    private static final VoxelShape SOUTH_AABB = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 3.0D);
    private static final VoxelShape NORTH_AABB = Block.box(0.0D, 0.0D, 13.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape WEST_AABB = Block.box(13.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape EAST_AABB = Block.box(0.0D, 0.0D, 0.0D, 3.0D, 16.0D, 16.0D);

    public EmeraldDoorTopBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false)
                .setValue(HINGE, DoorHingeSide.LEFT));
    }

    @Override
    protected @NotNull MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public PathType getBlockPathType(BlockState state, BlockGetter level, BlockPos pos,
                                     @Nullable Mob mob) {
        return state.getValue(OPEN) ? PathType.DOOR_OPEN : PathType.DOOR_WOOD_CLOSED;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, HINGE);
    }

    /** Keeps the standalone third segment aligned when a structure rotates it. */
    @Override
    protected @NotNull BlockState rotate(@NotNull BlockState state, @NotNull Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    /** Mirrors both the panel direction and the hinge side like a vanilla door. */
    @Override
    protected @NotNull BlockState mirror(@NotNull BlockState state, @NotNull Mirror mirror) {
        return mirror == Mirror.NONE
                ? state
                : state.rotate(mirror.getRotation(state.getValue(FACING))).cycle(HINGE);
    }

    /**
     * This third segment is a separate block rather than a vanilla DoorBlock half.
     * Match vanilla door collision thickness while closed; a full cube here can
     * overlap tall mobs in the doorway and cause suffocation.
     */
    @Override
    protected @NotNull VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                                      @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return getDoorPanelShape(state);
    }

    /**
     * The default block selection shape is a full cube. Use the rendered panel
     * shape here as well so the top segment does not retain a full-cube hitbox.
     */
    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                             @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return getDoorPanelShape(state);
    }

    private static VoxelShape getDoorPanelShape(BlockState state) {
        boolean closed = !state.getValue(OPEN);
        boolean rightHinge = state.getValue(HINGE) == DoorHingeSide.RIGHT;

        return switch (state.getValue(FACING)) {
            case SOUTH -> closed ? SOUTH_AABB : rightHinge ? EAST_AABB : WEST_AABB;
            case WEST -> closed ? WEST_AABB : rightHinge ? SOUTH_AABB : NORTH_AABB;
            case NORTH -> closed ? NORTH_AABB : rightHinge ? WEST_AABB : EAST_AABB;
            default -> closed ? EAST_AABB : rightHinge ? NORTH_AABB : SOUTH_AABB;
        };
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level,
                                                         @NotNull BlockPos pos, @NotNull Player player,
                                                         @NotNull BlockHitResult hitResult) {
        BlockPos lowerDoorPos = pos.below(2);
        BlockState lowerState = level.getBlockState(lowerDoorPos);
        if (!lowerState.is(ECAPBlocks.EMERALD_DOOR.get())) {
            return InteractionResult.PASS;
        }

        boolean open = !lowerState.getValue(BlockStateProperties.OPEN);
        DoorPairingUtils.setDoorAndPairedOpen(level, lowerDoorPos, open, Block.UPDATE_ALL);
        level.playSound(player, lowerDoorPos, open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0F, 0.9F);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                         @NotNull BlockState newState, boolean movedByPiston) {
        if (!newState.is(this)) {
            BlockPos upperDoorPos = pos.below();
            BlockPos lowerDoorPos = pos.below(2);

            if (level.getBlockState(upperDoorPos).is(ECAPBlocks.EMERALD_DOOR.get())) {
                level.destroyBlock(upperDoorPos, false);
            }
            if (level.getBlockState(lowerDoorPos).is(ECAPBlocks.EMERALD_DOOR.get())) {
                level.destroyBlock(lowerDoorPos, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
