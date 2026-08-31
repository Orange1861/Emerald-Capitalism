package com.orangevillager61.emeraldcapitalism.block.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.event.VillagerSpawnEvents;
import com.orangevillager61.emeraldcapitalism.menu.VillageManagerMenu;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class VillageManagerBlockEntity extends BlockEntity implements MenuProvider {

    // Constants

    /**
     * How often the VM scans for eligible depositors and for a bank block (if none is
     * registered). 12,000 ticks = 10 minutes.
     */
    public static final int DEPOSIT_SCAN_INTERVAL = 12000;

    /**
     * Radius (in blocks) used when searching for a bank block during placement or the
     * periodic scan. The bank's own chest-search radius is 8; we use a wider window
     * so the bank can be placed a comfortable distance from the VM.
     */
    public static final int BANK_SEARCH_RADIUS = 32;

    /**
     * Horizontal grace distance (in blocks) applied when scanning for registered
     * villagers to enqueue for deposits. This captures villagers that step just
     * outside the strict village boundary (e.g. one chunk over) without expanding
     * the scan volume vertically.
     */
    public static final int DEPOSIT_SCAN_HORIZONTAL_GRACE = 16;

    // Persistent state

    @Nullable
    private UUID villageId;

    /**
     * Position of the {@link BankBlockEntity} registered to this village manager, or
     * {@code null} if no bank has been linked.
     */
    @Nullable
    private BlockPos bankPos;

    /**
     * The mod-owned durable portion of a village manager. Runtime references to
     * levels, menus, and client synchronization buffers are intentionally not part
     * of this state; they are rebuilt or written at the point of use.
     */
    static record PersistedState(Optional<UUID> villageId, Optional<BlockPos> bankPos) {
        static final Codec<PersistedState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.optionalFieldOf("village_id")
                        .forGetter(PersistedState::villageId),
                BlockPos.CODEC.optionalFieldOf("bank_pos")
                        .forGetter(PersistedState::bankPos)
        ).apply(instance, PersistedState::new));

        static PersistedState empty() {
            return new PersistedState(Optional.empty(), Optional.empty());
        }

        static PersistedState from(@Nullable UUID villageId, @Nullable BlockPos bankPos) {
            return new PersistedState(Optional.ofNullable(villageId), Optional.ofNullable(bankPos));
        }

        @Nullable
        UUID villageIdOrNull() {
            return villageId.orElse(null);
        }

        @Nullable
        BlockPos bankPosOrNull() {
            return bankPos.orElse(null);
        }
    }

    // Ticker state (not persisted)

    private long nextDepositScanTick = Long.MIN_VALUE;

    // Constructor

    public VillageManagerBlockEntity(BlockPos pos, BlockState state) {
        super(ECAPBlockEntityTypes.VILLAGE_MANAGER.get(), pos, state);
    }

    // Static server ticker

    /**
     * Called every server tick by {@link com.orangevillager61.emeraldcapitalism.block.VillageManagerBlock#getTicker}.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, VillageManagerBlockEntity entity) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        long gameTime = serverLevel.getGameTime();
        if (entity.nextDepositScanTick == Long.MIN_VALUE) {
            entity.nextDepositScanTick = gameTime + DEPOSIT_SCAN_INTERVAL;
        } else if (gameTime >= entity.nextDepositScanTick) {
            entity.nextDepositScanTick = gameTime + DEPOSIT_SCAN_INTERVAL;
            entity.runPeriodicScan(serverLevel);
        }
    }

    // Periodic scan

    /**
     * Runs every {@link #DEPOSIT_SCAN_INTERVAL} ticks.
     * <ul>
     *   <li>If no bank is registered: resolves the persisted registered-bank link
     *       and reattaches it if that chunk is currently loaded.</li>
     *   <li>If a bank is registered: verifies it still exists, then enqueues all
     *       registered villagers currently holding more than
     *       {@link BankBlockEntity#MIN_EMERALDS_TO_DEPOSIT} emeralds.</li>
     * </ul>
     */
    private void runPeriodicScan(ServerLevel level) {
        if (villageId == null) return;

        VillageRegistryData registryData = VillageRegistryData.get(level);
        VillageRecord village = registryData.getVillages().get(villageId);
        if (village == null) return;

        if (bankPos == null) {
            // Bank placement and manual bank placement register this link directly.
            // Do not synchronously scan every block in a potentially large village.
            BlockPos registeredBankPos = registryData.getBankPos(villageId);
            if (registeredBankPos == null || !level.isLoaded(registeredBankPos)) {
                return;
            }
            BlockEntity bankBE = level.getBlockEntity(registeredBankPos);
            if (bankBE instanceof BankBlockEntity bank
                    && (bank.getVillageId() == null || villageId.equals(bank.getVillageId()))) {
                registerBank(registeredBankPos);
            } else {
                registryData.deregisterBankPosition(villageId, registeredBankPos);
            }
        } else {
            // Validate the bank is still present
            if (!level.isLoaded(bankPos)) {
                // Chunk not loaded: skip this cycle but keep the reference
                return;
            }
            if (!level.getBlockState(bankPos).is(ECAPBlocks.BANK.get())) {
                // Bank was removed by some means other than a normal break event
                deregisterBank();
                return;
            }

            BlockEntity bankBE = level.getBlockEntity(bankPos);
            if (!(bankBE instanceof BankBlockEntity bank)) {
                return; // Entity not yet loaded: skip
            }

            populateDepositQueue(level, village, bank);
        }
    }

    /**
     * Scans a fixed radius around the VM's position for a {@link BankBlockEntity}.
     * Used during initial placement (before the village bounds are known).
     */
    @Nullable
    public BlockPos findNearbyBank(ServerLevel level) {
        BlockPos origin = getBlockPos();
        int r = BANK_SEARCH_RADIUS;
        for (BlockPos candidate : BlockPos.betweenClosed(
                origin.offset(-r, -r, -r),
                origin.offset(r, r, r))) {
            if (level.getBlockState(candidate).is(ECAPBlocks.BANK.get())) {
                return candidate.immutable();
            }
        }
        return null;
    }

    // Deposit queue population

    /**
     * Finds all registered villagers currently holding more than
     * {@link BankBlockEntity#MIN_EMERALDS_TO_DEPOSIT} emeralds and
     * enqueues them with the bank if they are not already in the queue.
     */
    private void populateDepositQueue(ServerLevel level, VillageRecord village, BankBlockEntity bank) {
        AABB villageBounds = village.getBoundingBox();
        AABB depositScanBounds = new AABB(
                villageBounds.minX - DEPOSIT_SCAN_HORIZONTAL_GRACE,
                villageBounds.minY,
                villageBounds.minZ - DEPOSIT_SCAN_HORIZONTAL_GRACE,
                villageBounds.maxX + DEPOSIT_SCAN_HORIZONTAL_GRACE,
                villageBounds.maxY,
                villageBounds.maxZ + DEPOSIT_SCAN_HORIZONTAL_GRACE
        );

        for (UUID villagerUUID : village.getMembers().keySet()) {
            if (!(level.getEntity(villagerUUID) instanceof Villager villager)
                    || !depositScanBounds.intersects(villager.getBoundingBox())) {
                continue;
            }
            VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            stats.refreshInventoryCounts(villager.getInventory());
            if (stats.getCachedEmeraldCount() > BankBlockEntity.MIN_EMERALDS_TO_DEPOSIT
                    && !bank.isQueued(villager.getUUID())) {
                bank.enqueue(villager.getUUID());
            }
        }
    }

    // Bank registration

    /**
     * Registers a bank at the given position. Opens bank accounts for all current
     * village members who do not already have one.
     */
    public void registerBank(BlockPos pos) {
        this.bankPos = pos.immutable();
        setChanged();

        Level lvl = getLevel();
        if (!(lvl instanceof ServerLevel serverLevel) || villageId == null) return;

        VillageRecord village = VillageRegistryData.get(serverLevel).getVillages().get(villageId);
        if (village == null) return;

        BankAccountData bankData = BankAccountData.get(serverLevel);

        // Assign a generated name to the bank if it does not yet have one
        BlockEntity bankBE = serverLevel.getBlockEntity(pos);
        BankBlockEntity bank = bankBE instanceof BankBlockEntity placedBank ? placedBank : null;
        if (bank != null) {
            bank.setVillageId(villageId);
            if (bank.getBankName().isEmpty()) {
                bank.setBankName(bankData.generateBankName());
            }
        }
        VillageRegistryData.get(serverLevel).registerBankPosition(villageId, pos);

        for (UUID memberUUID : village.getMembers().keySet()) {
            bankData.openAccount(memberUUID);
            if (bank != null && serverLevel.getEntity(memberUUID) instanceof Villager villager) {
                int initialEmeralds = VillagerSpawnEvents.getPendingInitialEmeralds(villager);
                if (initialEmeralds > 0) {
                    bank.depositInitialEmeralds(serverLevel, villager, initialEmeralds);
                    VillagerSpawnEvents.clearPendingInitialEmeralds(villager);
                }
            }
        }

        EmeraldCapitalism.LOGGER.info("[ECAP] Village manager at {} registered bank at {}",
                getBlockPos(), pos);
    }

    /**
     * Deregisters the bank from this VM. Does NOT clear the bank entity's queue
     * (the bank entity handles that when it receives the deregistration signal).
     */
    public void deregisterBank() {
        if (bankPos == null) return;

        // Clear the bank entity's queue if it is still loaded
        Level lvl = getLevel();
        if (lvl instanceof ServerLevel serverLevel && lvl.isLoaded(bankPos)) {
            BlockEntity be = serverLevel.getBlockEntity(bankPos);
            if (be instanceof BankBlockEntity bank) {
                bank.clearQueue();
            }
        }

        EmeraldCapitalism.LOGGER.info("[ECAP] Village manager at {} deregistered bank (was at {})",
                getBlockPos(), bankPos);
        if (lvl instanceof ServerLevel serverLevel && villageId != null) {
            VillageRegistryData.get(serverLevel).deregisterBankPosition(villageId, bankPos);
        }
        this.bankPos = null;
        setChanged();
    }

    public boolean hasBankRegistered() {
        return bankPos != null;
    }

    @Nullable
    public BlockPos getBankPos() {
        return bankPos;
    }

    // Village linkage

    @Nullable
    public UUID getVillageId() {
        return villageId;
    }

    public void setVillageId(@Nullable UUID villageId) {
        this.villageId = villageId;
        setChanged();
    }

    /**
     * Attempts to auto-detect the village this block sits inside, using the SavedData bounding boxes.
     */
    public void autoDetectVillage() {
        Level level = getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        VillageRegistryData data = VillageRegistryData.get(serverLevel);
        VillageRecord record = data.getVillageFor(getBlockPos());
        if (record != null) {
            setVillageId(record.getVillageId());
        }
    }

    // BlockEntity lifecycle

    /**
     * Called by NeoForge after this block entity has been attached to the level.
     * Registers this VM's position in {@link VillageRegistryData} so other systems
     * can look it up by village UUID.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        Level lvl = getLevel();
        if (lvl instanceof ServerLevel serverLevel && !lvl.isClientSide() && villageId != null) {
            VillageRegistryData.get(serverLevel).registerVillageManager(villageId, getBlockPos());
        }
    }

    /**
     * Called when this block entity is being removed from the level. Deregisters from
     * {@link VillageRegistryData} so stale lookups are not returned.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        Level lvl = getLevel();
        if (lvl instanceof ServerLevel serverLevel && !lvl.isClientSide() && villageId != null) {
            VillageRegistryData.get(serverLevel).deregisterVillageManager(villageId);
        }
    }

    // NBT serialization

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        PersistedState.CODEC.encodeStart(NbtOps.INSTANCE,
                        PersistedState.from(villageId, bankPos))
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "Could not encode village manager durable state: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .ifPresent(tag::merge);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        PersistedState state = PersistedState.CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                        "Ignoring malformed village manager durable state: {}", message))
                .orElseGet(PersistedState::empty);
        villageId = state.villageIdOrNull();
        bankPos = state.bankPosOrNull();
    }

    // MenuProvider

    @Override
    public @NotNull Component getDisplayName() {
        return Component.translatable("block.emeraldcapitalism.village_manager");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, @NotNull Inventory playerInventory, @NotNull Player player) {
        return new VillageManagerMenu(containerId, playerInventory, this);
    }

    /**
     * Writes extra data for the client-side menu constructor.
     *
     * <p>This must be provided by the menu provider itself. Spectator interaction
     * opens a block entity's {@link MenuProvider} directly and bypasses the block's
     * normal {@code useWithoutItem} path.</p>
     */
    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        writeMenuOpenData(buf);
    }

    public void writeMenuOpenData(FriendlyByteBuf buf) {
        buf.writeBlockPos(getBlockPos());
        buf.writeBoolean(villageId != null);
        if (villageId != null) {
            buf.writeUUID(villageId);
        }

        // Resolve village data and send summary info to client
        String villageName = "";
        BlockPos bellPos = BlockPos.ZERO;
        double minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        int memberCount = 0;

        if (villageId != null && getLevel() instanceof ServerLevel serverLevel) {
            VillageRegistryData data = VillageRegistryData.get(serverLevel);
            VillageRecord record = data.getVillages().get(villageId);
            if (record != null) {
                bellPos = record.getBellPosition();
                var bb = record.getBoundingBox();
                minX = bb.minX;
                minY = bb.minY;
                minZ = bb.minZ;
                maxX = bb.maxX;
                maxY = bb.maxY;
                maxZ = bb.maxZ;
                memberCount = record.getMembers().size();
                villageName = villageId.toString().substring(0, 8);
            }
        }

        buf.writeUtf(ProtocolStringLimits.clamp(villageName, ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH),
                ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
        buf.writeBlockPos(bellPos);
        buf.writeDouble(minX);
        buf.writeDouble(minY);
        buf.writeDouble(minZ);
        buf.writeDouble(maxX);
        buf.writeDouble(maxY);
        buf.writeDouble(maxZ);
        buf.writeInt(memberCount);
    }
}
