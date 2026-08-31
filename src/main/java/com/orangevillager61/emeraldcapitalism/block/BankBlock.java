package com.orangevillager61.emeraldcapitalism.block;

import com.mojang.serialization.MapCodec;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

/**
 * The Bank block: a village infrastructure block that tracks all {@link EmeraldChestBlock}
 * instances within a 16×16×16 cube centred on itself.
 * <p>
 * On placement this block attempts to:
 * <ol>
 *   <li>Link itself to the containing village (via {@link VillageRegistryData}).</li>
 *   <li>Notify the village manager for that village so it can register this bank.</li>
 *   <li>If the village manager already has a bank, break this duplicate and drop it.</li>
 * </ol>
 * <p>
 * The block cannot be broken while its linked chests hold any villager funds;
 * that check is enforced in
 * {@link com.orangevillager61.emeraldcapitalism.event.VillageRegistryEvents#onBlockBroken}.
 */
public class BankBlock extends BaseEntityBlock {

    public static final MapCodec<BankBlock> CODEC = simpleCodec(BankBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    /** Controlled banks stop exposing their block state as an acquirable banker POI. */
    public static final BooleanProperty BANKER_AVAILABLE = BooleanProperty.create("banker_available");

    /**
     * Bank side convention, using Minecraft's named cardinal directions
     * ({@code NORTH}, {@code WEST}, {@code EAST}, and {@code SOUTH}) from the
     * block state rather than a villager's current heading:
     * <ul>
     *     <li>The side named by {@link #FACING} is the villager deposit approach side.</li>
     *     <li>The opposite side is the banker work side.</li>
     * </ul>
     */

    /** Returns the side where villagers approach to deposit. */
    public static BlockPos getDepositApproachPos(BlockState state, BlockPos bankPos) {
        return bankPos.relative(state.getValue(FACING));
    }

    /** Returns the side where the banker works at the bank. */
    public static BlockPos getBankerWorkPos(BlockState state, BlockPos bankPos) {
        return bankPos.relative(state.getValue(FACING).getOpposite());
    }

    // Match the vanilla lectern's collision and selection shapes.
    private static final VoxelShape SHAPE_BASE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D);
    private static final VoxelShape SHAPE_POST = Block.box(4.0D, 2.0D, 4.0D, 12.0D, 14.0D, 12.0D);
    private static final VoxelShape SHAPE_COMMON = Shapes.or(SHAPE_BASE, SHAPE_POST);
    private static final VoxelShape SHAPE_TOP_PLATE = Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_COLLISION = Shapes.or(SHAPE_COMMON, SHAPE_TOP_PLATE);
    private static final VoxelShape SHAPE_WEST = Shapes.or(
            Block.box(1.0D, 10.0D, 0.0D, 5.333333D, 14.0D, 16.0D),
            Block.box(5.333333D, 12.0D, 0.0D, 9.666667D, 16.0D, 16.0D),
            Block.box(9.666667D, 14.0D, 0.0D, 14.0D, 18.0D, 16.0D),
            SHAPE_COMMON);
    private static final VoxelShape SHAPE_NORTH = Shapes.or(
            Block.box(0.0D, 10.0D, 1.0D, 16.0D, 14.0D, 5.333333D),
            Block.box(0.0D, 12.0D, 5.333333D, 16.0D, 16.0D, 9.666667D),
            Block.box(0.0D, 14.0D, 9.666667D, 16.0D, 18.0D, 14.0D),
            SHAPE_COMMON);
    private static final VoxelShape SHAPE_EAST = Shapes.or(
            Block.box(10.666667D, 10.0D, 0.0D, 15.0D, 14.0D, 16.0D),
            Block.box(6.333333D, 12.0D, 0.0D, 10.666667D, 16.0D, 16.0D),
            Block.box(2.0D, 14.0D, 0.0D, 6.333333D, 18.0D, 16.0D),
            SHAPE_COMMON);
    private static final VoxelShape SHAPE_SOUTH = Shapes.or(
            Block.box(0.0D, 10.0D, 10.666667D, 16.0D, 14.0D, 15.0D),
            Block.box(0.0D, 12.0D, 6.333333D, 16.0D, 16.0D, 10.666667D),
            Block.box(0.0D, 14.0D, 2.0D, 16.0D, 18.0D, 6.333333D),
            SHAPE_COMMON);

    public BankBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(BANKER_AVAILABLE, true));
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, BANKER_AVAILABLE);
    }

    /** Returns this bank state with the dynamic banker job-site flag applied. */
    public static BlockState withBankerJobAvailability(BlockState state, boolean available) {
        return state.hasProperty(BANKER_AVAILABLE)
                ? state.setValue(BANKER_AVAILABLE, available) : state;
    }

    @Override
    protected @NotNull VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                                      @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPE_COLLISION;
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                             @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case NORTH -> SHAPE_NORTH;
            default -> SHAPE_EAST;
        };
    }

    @Override
    protected @NotNull VoxelShape getOcclusionShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                                      @NotNull BlockPos pos) {
        return SHAPE_COMMON;
    }

    @Override
    protected boolean useShapeForLightOcclusion(@NotNull BlockState state) {
        return true;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new BankBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level level,
            @NotNull BlockState state,
            @NotNull BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type,
                com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes.BANK.get(),
                BankBlockEntity::serverTick);
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level,
                                                         @NotNull BlockPos pos, @NotNull Player player,
                                                         @NotNull BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BankBlockEntity bank && player instanceof ServerPlayer serverPlayer) {
            if (bank.getVillageId() == null) {
                bank.autoDetectVillage();
            }
            serverPlayer.openMenu(bank);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * After the block is placed:
     * <ol>
     *   <li>Links the bank entity to its village.</li>
     *   <li>Looks up the village manager for that village.</li>
     *   <li>If the VM already has a bank, breaks this duplicate (drops the item).</li>
     *   <li>Otherwise registers this bank with the VM.</li>
     * </ol>
     */
    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                            @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (!(level instanceof ServerLevel serverLevel)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BankBlockEntity bank)) return;

        ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        VillageRegistryData data = VillageRegistryData.get(overworld);
        VillageRecord record = data.getVillageFor(pos);
        if (record == null) return; // Not inside any village: bank sits unlinked until VM scans

        bank.setVillageId(record.getVillageId());

        // Notify the village manager for this village
        BlockPos vmPos = data.getVMPos(record.getVillageId());
        if (vmPos == null) return; // No VM placed yet: VM will find bank on its next scan

        BlockEntity vmBE = serverLevel.getBlockEntity(vmPos);
        if (!(vmBE instanceof VillageManagerBlockEntity vm)) return;

        if (vm.hasBankRegistered()) {
            // Duplicate bank: break it and drop the item
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Duplicate bank placed at {} for village {} "
                            + "(bank already registered at {}), removing duplicate",
                    pos, record.getVillageId(), vm.getBankPos());
            level.destroyBlock(pos, true);
        } else {
            vm.registerBank(pos);
        }
    }

    @Override
    protected @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }
}
