package com.orangevillager61.emeraldcapitalism.block;

import com.mojang.serialization.MapCodec;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.orangevillager61.emeraldcapitalism.world.village.VillageManagerPlacement;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.UUID;

public class VillageManagerBlock extends BaseEntityBlock {

    public static final MapCodec<VillageManagerBlock> CODEC = simpleCodec(VillageManagerBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    public VillageManagerBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new VillageManagerBlockEntity(pos, state);
    }

    /**
     * Registers a server-side ticker on {@link VillageManagerBlockEntity}.
     * The ticker handles the periodic deposit-queue population scan (every 12,000 ticks).
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            @NotNull Level level,
            @NotNull BlockState state,
            @NotNull BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type,
                ECAPBlockEntityTypes.VILLAGE_MANAGER.get(),
                VillageManagerBlockEntity::serverTick);
    }

    @Override
    protected @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof VillageManagerBlockEntity villageManager && player instanceof ServerPlayer serverPlayer) {
            // Auto-detect village on first use if not yet linked
            if (villageManager.getVillageId() == null) {
                villageManager.autoDetectVillage();
            }
            serverPlayer.openMenu(villageManager);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state, @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof VillageManagerBlockEntity villageManager)) {
            return;
        }

        // Village records are scoped to the dimension containing the manager.
        VillageRegistryData data = VillageRegistryData.get(serverLevel);
        UUID placerId = placer instanceof Player player ? player.getUUID() : null;

        // 1. Check if already inside or near an existing village
        VillageRecord existing = data.getNearestVillage(pos);
        if (existing != null) {
            double distSq = existing.getBellPosition().distSqr(pos);
            AABB bb = existing.getBoundingBox();
            double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
            boolean inside = bb.contains(x, y, z);
            // Link if inside bounding box or within 16 blocks of bell
            if (inside || distSq <= 16 * 16) {
                if (hasConflictingVillageOwner(existing, placerId)
                        || hasConflictingVillageManager(data, existing, pos)
                        || hasConflictingNearbyBank(serverLevel, villageManager,
                        existing.getVillageId(), placerId)) {
                    rejectPlacement(level, pos, placer);
                    return;
                }

                villageManager.setVillageId(existing.getVillageId());
                data.registerVillageManager(existing.getVillageId(), pos);
                appointGovernor(data, existing, placerId);
                if (placer instanceof ServerPlayer player) {
                    com.orangevillager61.emeraldcapitalism.util.PlayerMessageUtils.send(player, Component.literal(
                            "[Village Manager] Linked to existing village " + existing.getVillageId().toString().substring(0, 8)
                    ));
                }
                // Scan nearby for a bank block and register it
                tryRegisterNearbyBank(serverLevel, villageManager, pos);
                return;
            }
        }

        // 2. Look for an unregistered bell nearby
        BlockPos bellPos = VillageManagerPlacement.findNearestBell(serverLevel, pos, 32);
        if (bellPos != null) {
            if (hasConflictingNearbyBank(serverLevel, villageManager, null, placerId)) {
                rejectPlacement(level, pos, placer);
                return;
            }
            UUID villageId = UUID.randomUUID();
            AABB bounds = new AABB(bellPos).inflate(128, 48, 128);
            VillageRecord village = data.getOrCreateVillage(villageId, bellPos, bounds);
            appointGovernor(data, village, placerId);
            data.setDirty();
            villageManager.setVillageId(villageId);
            data.registerVillageManager(villageId, pos);
            if (placer instanceof ServerPlayer player) {
                com.orangevillager61.emeraldcapitalism.util.PlayerMessageUtils.send(player, Component.literal(
                        "[Village Manager] Discovered unregistered village and linked"
                ));
            }
            tryRegisterNearbyBank(serverLevel, villageManager, pos);
            return;
        }

        // 3. No village or bell nearby: create a new village area centered on block
        if (hasConflictingNearbyBank(serverLevel, villageManager, null, placerId)) {
            rejectPlacement(level, pos, placer);
            return;
        }
        UUID villageId = UUID.randomUUID();
        AABB bounds = new AABB(pos).inflate(64, 32, 64);
        VillageRecord village = data.getOrCreateVillage(villageId, pos, bounds);
        appointGovernor(data, village, placerId);
        data.setDirty();
        villageManager.setVillageId(villageId);
        data.registerVillageManager(villageId, pos);
        if (placer instanceof ServerPlayer player) {
            com.orangevillager61.emeraldcapitalism.util.PlayerMessageUtils.send(player, Component.literal(
                    "[Village Manager] Created new village area"
            ));
        }
        tryRegisterNearbyBank(serverLevel, villageManager, pos);
    }

    /**
     * Scans within {@link VillageManagerBlockEntity#BANK_SEARCH_RADIUS} blocks of the VM
     * for an unregistered or same-village bank, and calls
     * {@link VillageManagerBlockEntity#registerBank} if found.
     * <p>
     * If the found bank is already registered to this VM (same village), nothing changes.
     */
    private static void tryRegisterNearbyBank(ServerLevel level,
                                               VillageManagerBlockEntity vm,
                                               BlockPos vmPos) {
        if (vm.hasBankRegistered()) return; // already has one
        BlockPos found = vm.findNearbyBank(level);
        if (found == null) return;

        BlockEntity bankBE = level.getBlockEntity(found);
        if (!(bankBE instanceof BankBlockEntity bank)) return;

        UUID bankVillage = bank.getVillageId();
        UUID vmVillage = vm.getVillageId();
        // Only adopt if the bank is unlinked or linked to the same village
        if (bankVillage == null || bankVillage.equals(vmVillage)) {
            vm.registerBank(found);
        }
    }

    private static boolean hasConflictingVillageOwner(VillageRecord record, @Nullable UUID placerId) {
        UUID governorId = record.getGovernorId();
        return governorId != null && placerId != null && !governorId.equals(placerId);
    }

    private static boolean hasConflictingVillageManager(VillageRegistryData data,
                                                         VillageRecord record,
                                                         BlockPos pos) {
        BlockPos managerPos = data.getVMPos(record.getVillageId());
        return managerPos != null && !managerPos.equals(pos);
    }

    private static boolean hasConflictingNearbyBank(ServerLevel level,
                                                     VillageManagerBlockEntity villageManager,
                                                     @Nullable UUID villageId,
                                                     @Nullable UUID placerId) {
        BlockPos bankPos = villageManager.findNearbyBank(level);
        if (bankPos == null) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(bankPos);
        if (!(blockEntity instanceof BankBlockEntity bank)) {
            return false;
        }

        UUID bankVillageId = bank.getVillageId();
        // An unlinked bank is available for this ledger to claim. Its controller
        // alone must not turn the pairing into a proximity conflict.
        if (bankVillageId == null) {
            return false;
        }
        if (!bankVillageId.equals(villageId)) {
            return true;
        }

        UUID bankOwnerId = bank.getControllerId();
        return bankOwnerId != null && placerId != null && !bankOwnerId.equals(placerId);
    }

    private static void appointGovernor(VillageRegistryData data,
                                        VillageRecord village,
                                        @Nullable UUID placerId) {
        if (placerId != null && village.setGovernor(placerId)) {
            data.setDirty();
        }
    }

    private static void rejectPlacement(Level level, BlockPos pos, @Nullable LivingEntity placer) {
        if (placer instanceof Player player) {
            com.orangevillager61.emeraldcapitalism.util.PlayerMessageUtils.send(
                    player, Component.literal("Too Close to Nearby Bank or Village"));
        }
        level.destroyBlock(pos, true);
    }
}
