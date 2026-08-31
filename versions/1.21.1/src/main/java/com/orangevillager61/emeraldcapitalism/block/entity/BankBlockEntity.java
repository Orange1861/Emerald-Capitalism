package com.orangevillager61.emeraldcapitalism.block.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.entity.ai.BankDepositGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.VaultGolemGoals;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import com.orangevillager61.emeraldcapitalism.menu.BankMenuOpenData;
import com.orangevillager61.emeraldcapitalism.market.MarketItem;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.bank.BankTargets;
import com.orangevillager61.emeraldcapitalism.world.bank.EmeraldGolemCalculator;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Block entity for {@link com.orangevillager61.emeraldcapitalism.block.BankBlock}.
 * Caches linked {@link EmeraldChestBlockEntity} positions and the nearest
 * {@link EmeraldOreProcessorBlockEntity} within the search cube.
 * The ticker advances the deposit queue, while full scans and cheaper verification
 * passes keep the cache synchronized with the world.
 */
public class BankBlockEntity extends BlockEntity implements MenuProvider {

    /** Loaded banks indexed by level so chest/processor updates do not scan a block cube. */
    private static final Map<ServerLevel, Set<BankBlockEntity>> LOADED_BANKS = new IdentityHashMap<>();

    // Scanning constants

    /** Half-extent of the search cube in each axis (8 blocks → 16×16×16 cube). */
    public static final int SEARCH_RADIUS = 8;

    /** Ticks between full area scans (default: 1200 t = 60 s). */
    public static final int FULL_SCAN_INTERVAL = 1200;

    /** Ticks between verify passes (default: 40 t = 2 s). */
    public static final int VERIFY_INTERVAL = 40;

    /** Minimum raw emerald count required for a villager to join the deposit queue. */
    public static final int MIN_EMERALDS_TO_DEPOSIT = 15;

    /** Safety bound for the durable list of bank chest locations. */
    public static final int MAX_TRACKED_CHEST_LOCATIONS = 4096;

    // State

    @Nullable
    private UUID villageId;

    /** True by default; when false, the bank is controlled by controllerId. */
    private boolean bankIndependent = true;
    @Nullable
    private UUID controllerId;

    /** Display name of this bank (e.g. "Bank 1"). Empty string means not yet assigned. */
    private String bankName = "";

    /**
     * Cached emerald-chest positions inside the search volume, in insertion order.
     * Insertion order is used as the "registration order" for chest deposit.
     */
    private final LinkedHashSet<BlockPos> cachedChestPositions = new LinkedHashSet<>();
    private final Map<BlockPos, EmeraldChestBlockEntity.StoredCounts> cachedChestTotals = new HashMap<>();
    /** Durable chest locations retained when a bank chest is broken or missing. */
    private final LinkedHashSet<BlockPos> trackedChestPositions = new LinkedHashSet<>();
    /** Chests crafted by an Emeraldsmith and reserved for tracked missing locations. */
    private int preparedEmeraldChestCount;
    @Nullable
    private BlockPos closestEmeraldProcessorPos;

    private int totalEmeraldCount = 0;
    private int totalEmeraldBlockCount = 0;
    private int totalEmeraldOreCount = 0;
    private int totalPumpkinCount = 0;
    private int totalWheatCount = 0;
    private int totalBreadCount = 0;
    private int totalCoalCount = 0;
    private int totalEmeraldGreenDyeCount = 0;
    /** Plank-equivalent stock: one log contributes four planks. */
    private int totalPlankCount = 0;

    private long nextFullScanTick = Long.MIN_VALUE;
    private long nextVerifyTick = Long.MIN_VALUE;
    private boolean chestCacheDirty = true;
    private boolean processorCacheDirty = true;

    // Deposit queue state (not persisted, cleared on chunk reload)

    /** Villagers waiting to make a deposit, in FIFO order. */
    private final Queue<UUID> depositQueue = new ArrayDeque<>();
    /** O(1) membership companion for {@link #depositQueue}. */
    private final Set<UUID> queuedDepositors = new java.util.HashSet<>();

    /** The villager currently being routed to the bank. */
    @Nullable
    private UUID currentDepositor = null;

    /** The active {@link BankDepositGoal} attached to the current depositor, or null. */
    @Nullable
    private BankDepositGoal activeGoal = null;

    /**
     * Goals scheduled for removal on the next bank tick.
     * Deferred to avoid {@link java.util.ConcurrentModificationException} inside the
     * goal-scheduler iteration loop when {@link BankDepositGoal#stop()} is called.
     */
    private final List<BankDepositGoal> pendingGoalRemovals = new ArrayList<>();
    /** Server-side close deadlines for Emerald Chests opened by smith transfers. */
    private final Map<BlockPos, Long> transferChestCloseTicks = new HashMap<>();

    /** Maximum number of villagers this Bank can employ. */
    public static final int MAX_EMPLOYEES = 3;

    /** Thirty minutes of exclusive takeover rights, expressed in game ticks. */
    public static final long DIRECT_KILL_TAKEOVER_LOCK_TICKS = 30L * 60L * 20L;

    /** Safety bound for the durable guard-assignment list. */
    public static final int MAX_PERSISTED_GOLEM_EMPLOYEES = 4096;

    /** Villagers assigned to this Bank, in the order they were registered. */
    private final LinkedHashSet<UUID> employeeIds = new LinkedHashSet<>();
    /** Employees whose assignment came from one of the Bank's job sites. */
    private final LinkedHashSet<UUID> jobEmployeeIds = new LinkedHashSet<>();
    /** Emerald golems spawned as guards for this Bank's vault. */
    private final LinkedHashSet<UUID> emeraldGolemEmployeeIds = new LinkedHashSet<>();

    /** Player allowed to claim this bank after directly killing its last employee. */
    @Nullable
    private UUID takeoverLockPlayer;
    /** Absolute server game-time tick at which the direct-kill takeover lock expires. */
    private long takeoverLockUntil;

    /** Generated anchor position for Emerald Golem construction. */
    @Nullable
    private BlockPos golemConstructionPos;

    /** Transient server-side reservation preventing two smiths from building at once. */
    @Nullable
    private UUID activeGolemConstructionVillager;

    /** The composter job site discovered through one of this Bank's employees. */
    @Nullable
    private BlockPos composterPos;

    /** Durable mod-owned state; live caches and work queues are rebuilt after reload. */
    static record PersistedState(
            Optional<UUID> villageId,
            boolean bankIndependent,
            Optional<UUID> controllerId,
            String bankName,
            List<UUID> employeeIds,
            List<UUID> jobEmployeeIds,
            List<UUID> emeraldGolemEmployeeIds,
            List<BlockPos> trackedChestPositions,
            int preparedEmeraldChestCount,
            Optional<BlockPos> composterPos,
            Optional<BlockPos> golemConstructionPos,
            Optional<UUID> takeoverLockPlayer,
            long takeoverLockUntil
    ) {
        PersistedState(Optional<UUID> villageId, String bankName, List<UUID> employeeIds,
                       List<UUID> jobEmployeeIds, List<UUID> emeraldGolemEmployeeIds,
                       Optional<BlockPos> composterPos, Optional<BlockPos> golemConstructionPos) {
            this(villageId, true, Optional.empty(), bankName, employeeIds, jobEmployeeIds,
                    emeraldGolemEmployeeIds, List.of(), 0, composterPos, golemConstructionPos,
                    Optional.empty(), 0L);
        }

        PersistedState(Optional<UUID> villageId, boolean bankIndependent, Optional<UUID> controllerId,
                       String bankName, List<UUID> employeeIds, List<UUID> jobEmployeeIds,
                       List<UUID> emeraldGolemEmployeeIds, Optional<BlockPos> composterPos,
                       Optional<BlockPos> golemConstructionPos, Optional<UUID> takeoverLockPlayer,
                       long takeoverLockUntil) {
            this(villageId, bankIndependent, controllerId, bankName, employeeIds, jobEmployeeIds,
                    emeraldGolemEmployeeIds, List.of(), 0, composterPos, golemConstructionPos,
                    takeoverLockPlayer, takeoverLockUntil);
        }

        private static final Codec<String> BANK_NAME_CODEC = Codec.STRING.validate(name ->
                name.length() <= ProtocolStringLimits.MAX_BANK_NAME_LENGTH
                        ? com.mojang.serialization.DataResult.success(name)
                        : com.mojang.serialization.DataResult.error(() ->
                        "Bank name exceeds " + ProtocolStringLimits.MAX_BANK_NAME_LENGTH + " characters"));
        private static final Codec<List<UUID>> EMPLOYEE_IDS_CODEC =
                UUIDUtil.CODEC.sizeLimitedListOf(MAX_EMPLOYEES);
        private static final Codec<List<UUID>> GOLEM_EMPLOYEE_IDS_CODEC =
                UUIDUtil.CODEC.sizeLimitedListOf(MAX_PERSISTED_GOLEM_EMPLOYEES);
        private static final Codec<List<BlockPos>> TRACKED_CHEST_POSITIONS_CODEC =
                BlockPos.CODEC.sizeLimitedListOf(MAX_TRACKED_CHEST_LOCATIONS);

        static final Codec<PersistedState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.optionalFieldOf("village_id")
                        .forGetter(PersistedState::villageId),
                Codec.BOOL.optionalFieldOf("bank_independent", true)
                        .forGetter(PersistedState::bankIndependent),
                UUIDUtil.CODEC.optionalFieldOf("controller_id")
                        .forGetter(PersistedState::controllerId),
                BANK_NAME_CODEC.optionalFieldOf("bank_name", "")
                        .forGetter(PersistedState::bankName),
                EMPLOYEE_IDS_CODEC.optionalFieldOf("employee_ids", List.of())
                        .forGetter(PersistedState::employeeIds),
                EMPLOYEE_IDS_CODEC.optionalFieldOf("job_employee_ids", List.of())
                        .forGetter(PersistedState::jobEmployeeIds),
                GOLEM_EMPLOYEE_IDS_CODEC.optionalFieldOf("emerald_golem_employee_ids", List.of())
                        .forGetter(PersistedState::emeraldGolemEmployeeIds),
                TRACKED_CHEST_POSITIONS_CODEC.optionalFieldOf("tracked_chest_positions", List.of())
                        .forGetter(PersistedState::trackedChestPositions),
                Codec.intRange(0, MAX_TRACKED_CHEST_LOCATIONS)
                        .optionalFieldOf("prepared_emerald_chest_count", 0)
                        .forGetter(PersistedState::preparedEmeraldChestCount),
                BlockPos.CODEC.optionalFieldOf("composter_pos")
                        .forGetter(PersistedState::composterPos),
                BlockPos.CODEC.optionalFieldOf("golem_construction_pos")
                        .forGetter(PersistedState::golemConstructionPos),
                UUIDUtil.CODEC.optionalFieldOf("takeover_lock_player")
                        .forGetter(PersistedState::takeoverLockPlayer),
                Codec.LONG.optionalFieldOf("takeover_lock_until", 0L)
                        .forGetter(PersistedState::takeoverLockUntil)
        ).apply(instance, PersistedState::new));

        PersistedState {
            Objects.requireNonNull(villageId, "villageId");
            Objects.requireNonNull(controllerId, "controllerId");
            Objects.requireNonNull(bankName, "bankName");
            Objects.requireNonNull(employeeIds, "employeeIds");
            Objects.requireNonNull(jobEmployeeIds, "jobEmployeeIds");
            Objects.requireNonNull(emeraldGolemEmployeeIds, "emeraldGolemEmployeeIds");
            Objects.requireNonNull(trackedChestPositions, "trackedChestPositions");
            Objects.requireNonNull(composterPos, "composterPos");
            Objects.requireNonNull(golemConstructionPos, "golemConstructionPos");
            Objects.requireNonNull(takeoverLockPlayer, "takeoverLockPlayer");
            if (bankIndependent) {
                controllerId = Optional.empty();
            } else if (controllerId.isEmpty()) {
                bankIndependent = true;
            }
            bankName = ProtocolStringLimits.clamp(
                    bankName.trim(),
                    ProtocolStringLimits.MAX_BANK_NAME_LENGTH);

            List<UUID> employees = copyUniqueAtMost(employeeIds, MAX_EMPLOYEES);
            employeeIds = employees;
            Set<UUID> employeeSet = Set.copyOf(employees);
            jobEmployeeIds = copyUniqueAtMost(jobEmployeeIds, MAX_EMPLOYEES).stream()
                    .filter(employeeSet::contains)
                    .toList();
            emeraldGolemEmployeeIds = copyUniqueAtMost(
                    emeraldGolemEmployeeIds, MAX_PERSISTED_GOLEM_EMPLOYEES);
            trackedChestPositions = copyUniquePositionsAtMost(
                    trackedChestPositions, MAX_TRACKED_CHEST_LOCATIONS);
            preparedEmeraldChestCount = Math.max(0,
                    Math.min(MAX_TRACKED_CHEST_LOCATIONS, preparedEmeraldChestCount));
            composterPos = copyPosition(composterPos);
            golemConstructionPos = copyPosition(golemConstructionPos);
            takeoverLockPlayer = takeoverLockPlayer == null ? Optional.empty() : takeoverLockPlayer;
            if (takeoverLockUntil < 0L || takeoverLockPlayer.isEmpty()) {
                takeoverLockPlayer = Optional.empty();
                takeoverLockUntil = 0L;
            }
        }

        static PersistedState empty() {
            return new PersistedState(
                    Optional.empty(), true, Optional.empty(), "", List.of(), List.of(), List.of(),
                    List.of(), 0, Optional.empty(), Optional.empty(), Optional.empty(), 0L);
        }

        static PersistedState from(BankBlockEntity bank) {
            return new PersistedState(
                    Optional.ofNullable(bank.villageId),
                    bank.bankIndependent,
                    Optional.ofNullable(bank.controllerId),
                    bank.bankName,
                    new ArrayList<>(bank.employeeIds),
                    new ArrayList<>(bank.jobEmployeeIds),
                    new ArrayList<>(bank.emeraldGolemEmployeeIds),
                    new ArrayList<>(bank.trackedChestPositions),
                    bank.preparedEmeraldChestCount,
                    Optional.ofNullable(bank.composterPos),
                    Optional.ofNullable(bank.golemConstructionPos),
                    Optional.ofNullable(bank.takeoverLockPlayer),
                    bank.takeoverLockUntil);
        }

        void applyTo(BankBlockEntity bank) {
            bank.villageId = villageId.orElse(null);
            bank.bankIndependent = bankIndependent;
            bank.controllerId = controllerId.orElse(null);
            bank.bankName = bankName;
            bank.employeeIds.clear();
            bank.employeeIds.addAll(employeeIds);
            bank.jobEmployeeIds.clear();
            bank.jobEmployeeIds.addAll(jobEmployeeIds);
            bank.emeraldGolemEmployeeIds.clear();
            bank.emeraldGolemEmployeeIds.addAll(emeraldGolemEmployeeIds);
            bank.trackedChestPositions.clear();
            bank.trackedChestPositions.addAll(trackedChestPositions);
            bank.preparedEmeraldChestCount = preparedEmeraldChestCount;
            bank.composterPos = composterPos.orElse(null);
            bank.golemConstructionPos = golemConstructionPos.orElse(null);
            bank.takeoverLockPlayer = takeoverLockPlayer.orElse(null);
            bank.takeoverLockUntil = takeoverLockUntil;
        }

        private static <T> List<T> copyUniqueAtMost(List<T> values, int maxSize) {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) {
                return List.of();
            }
            if (values.size() > maxSize) {
                throw new IllegalArgumentException("Persisted list exceeds " + maxSize + " entries");
            }
            LinkedHashSet<T> unique = new LinkedHashSet<>();
            for (T value : values) {
                Objects.requireNonNull(value, "values contains null");
                unique.add(value);
                if (unique.size() == maxSize) {
                    break;
                }
            }
            return List.copyOf(unique);
        }

        private static List<BlockPos> copyUniquePositionsAtMost(List<BlockPos> values, int maxSize) {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) {
                return List.of();
            }
            if (values.size() > maxSize) {
                throw new IllegalArgumentException("Persisted position list exceeds " + maxSize + " entries");
            }
            LinkedHashSet<BlockPos> unique = new LinkedHashSet<>();
            for (BlockPos value : values) {
                Objects.requireNonNull(value, "values contains null");
                unique.add(value.immutable());
                if (unique.size() == maxSize) {
                    break;
                }
            }
            return List.copyOf(unique);
        }

        private static Optional<BlockPos> copyPosition(Optional<BlockPos> position) {
            return Objects.requireNonNull(position, "position").map(BlockPos::immutable);
        }
    }

    // Constructor

    public BankBlockEntity(BlockPos pos, BlockState state) {
        super(ECAPBlockEntityTypes.BANK.get(), pos, state);
    }

    // Server tick

    /**
     * Static ticker wired up by {@link com.orangevillager61.emeraldcapitalism.block.BankBlock#getTicker}.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, BankBlockEntity entity) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Process the deposit queue every tick while there is work to do
        entity.processDepositQueue(serverLevel);

        long gameTime = serverLevel.getGameTime();
        entity.tickTransferChestAnimations(serverLevel, gameTime);
        entity.initializeScanSchedule(gameTime);
        if (entity.chestCacheDirty || entity.processorCacheDirty || gameTime >= entity.nextFullScanTick) {
            PerformanceTimingCounters.measure(
                    PerformanceTimingCounters.Operation.BANK_CHEST_CACHE_REBUILD,
                    () -> entity.fullScan(serverLevel));
            entity.chestCacheDirty = false;
            entity.processorCacheDirty = false;
            entity.nextFullScanTick = gameTime + FULL_SCAN_INTERVAL;
        } else if (gameTime >= entity.nextVerifyTick) {
            entity.verifyCachedChests(serverLevel);
            entity.nextVerifyTick = gameTime + VERIFY_INTERVAL;
        }
    }

    private void initializeScanSchedule(long gameTime) {
        if (nextFullScanTick != Long.MIN_VALUE) {
            return;
        }
        long offset = Math.floorMod(worldPosition.asLong(), FULL_SCAN_INTERVAL);
        nextFullScanTick = gameTime + offset;
        nextVerifyTick = gameTime + Math.floorMod(worldPosition.asLong(), VERIFY_INTERVAL);
    }

    /** Marks every loaded bank whose search cube contains a changed chest or processor. */
    public static void markChestCachesDirtyNear(ServerLevel level, BlockPos chestPos) {
        Set<BankBlockEntity> banks = LOADED_BANKS.get(level);
        if (banks == null) {
            return;
        }

        // Preserve the inclusive range used by the existing full-scan loop while
        // checking only loaded banks rather than every block in the surrounding cube.
        int minBankX = chestPos.getX() - SEARCH_RADIUS - 1;
        int minBankY = chestPos.getY() - SEARCH_RADIUS - 1;
        int minBankZ = chestPos.getZ() - SEARCH_RADIUS - 1;
        int maxBankX = chestPos.getX() + SEARCH_RADIUS;
        int maxBankY = chestPos.getY() + SEARCH_RADIUS;
        int maxBankZ = chestPos.getZ() + SEARCH_RADIUS;
        for (BankBlockEntity bank : banks) {
            BlockPos bankPos = bank.getBlockPos();
            if (bankPos.getX() >= minBankX && bankPos.getX() <= maxBankX
                    && bankPos.getY() >= minBankY && bankPos.getY() <= maxBankY
                    && bankPos.getZ() >= minBankZ && bankPos.getZ() <= maxBankZ) {
                bank.chestCacheDirty = true;
                bank.processorCacheDirty = true;
            }
        }
    }

    /** Finds the nearest loaded bank without forcing any chunks to load. */
    @Nullable
    public static BankBlockEntity findNearestLoadedBank(ServerLevel level, BlockPos origin) {
        Set<BankBlockEntity> banks = LOADED_BANKS.get(level);
        if (banks == null || banks.isEmpty()) {
            return null;
        }

        BankBlockEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        long nearestPosition = Long.MAX_VALUE;
        for (BankBlockEntity bank : banks) {
            if (bank.isRemoved()) {
                continue;
            }

            BlockPos bankPos = bank.getBlockPos();
            double distance = bankPos.distSqr(origin);
            long position = bankPos.asLong();
            if (distance < nearestDistance
                    || (Double.compare(distance, nearestDistance) == 0 && position < nearestPosition)) {
                nearest = bank;
                nearestDistance = distance;
                nearestPosition = position;
            }
        }
        return nearest;
    }

    /** Clears the loaded-bank index at a server lifecycle boundary. */
    public static void clearLoadedBanks() {
        LOADED_BANKS.clear();
    }

    /** Removes one unloaded level from the loaded-bank index. */
    public static void clearLoadedBanks(ServerLevel level) {
        LOADED_BANKS.remove(level);
    }

    // Deposit queue

    /**
     * Adds {@code uuid} to the back of the deposit queue if it is not already
     * queued or currently being processed.
     */
    public void enqueue(UUID uuid) {
        if (!uuid.equals(currentDepositor) && queuedDepositors.add(uuid)) {
            depositQueue.add(uuid);
        }
    }

    /**
     * Returns {@code true} if {@code uuid} is in the queue or is the current depositor.
     */
    public boolean isQueued(UUID uuid) {
        return uuid.equals(currentDepositor) || queuedDepositors.contains(uuid);
    }

    /** Read-only queue state used while building a menu snapshot. */
    int getDepositQueueSizeForMenu() {
        return depositQueue.size() + (currentDepositor != null ? 1 : 0);
    }

    @Nullable
    UUID getCurrentDepositorForMenu() {
        return currentDepositor;
    }

    List<UUID> getDepositQueueSnapshotForMenu() {
        return List.copyOf(depositQueue);
    }

    /** Queues a loaded villager immediately when their inventory is ready for a deposit. */
    public void queueDepositIfEligible(Villager villager) {
        UUID uuid = villager.getUUID();
        if (isQueued(uuid)) {
            return;
        }

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.refreshInventoryCounts(villager.getInventory());
        if (stats.getCachedEmeraldCount() > MIN_EMERALDS_TO_DEPOSIT) {
            enqueue(uuid);
        }
    }

    /**
     * Empties the deposit queue and resets all depositor tracking state.
     * Called when the bank is deregistered from its village manager.
     */
    public void clearQueue() {
        // Remove the active goal directly: safe here since we are outside goal-scheduler iteration
        if (activeGoal != null) {
            activeGoal.removeFromVillager();
            activeGoal = null;
        }
        pendingGoalRemovals.clear();
        depositQueue.clear();
        queuedDepositors.clear();
        currentDepositor = null;
    }

    /**
     * Called every tick to orchestrate the deposit queue.
     * Processes deferred goal removals, then advances to the next villager if idle.
     */
    private void processDepositQueue(ServerLevel level) {
        // Process deferred goal removals from the previous tick's stop() calls
        for (BankDepositGoal goal : pendingGoalRemovals) {
            goal.removeFromVillager();
        }
        pendingGoalRemovals.clear();

        // Villagers only travel to the Bank during the day. Leave the queue intact
        // overnight so the next daylight period can resume it.
        if (!level.isDay()) {
            return;
        }

        // Advance to the next queued villager if no current depositor
        if (currentDepositor == null) {
            if (depositQueue.isEmpty()) return;
            advanceToNext(level);
        } else if (activeGoal == null) {
            // Goal was not yet attached (e.g. villager was not loaded last tick): retry
            tryAttachGoal(level, currentDepositor);
        }
    }

    // Goal-orchestration methods (called by BankDepositGoal)

    /** Returns {@code true} if {@code uuid} is the villager currently at the front of the queue. */
    public boolean isCurrentDepositor(UUID uuid) {
        return uuid.equals(currentDepositor);
    }

    /**
     * Called by {@link BankDepositGoal#stop()} on successful arrival.
     * Nulls out the current depositor and goal so the next tick picks up the next entry.
     */
    public void advanceQueue() {
        currentDepositor = null;
        activeGoal = null;
    }

    /** Pauses the current depositor and puts them back at the end of the queue. */
    public void pauseCurrentDepositor() {
        if (currentDepositor == null) {
            return;
        }
        UUID pausedDepositor = currentDepositor;
        currentDepositor = null;
        activeGoal = null;
        enqueue(pausedDepositor);
    }

    /**
     * Called by {@link BankDepositGoal#stop()} after exhausting all pathfinding attempts.
     * Logs and skips the current depositor.
     */
    public void skipCurrent() {
        EmeraldCapitalism.LOGGER.warn(
                "[ECAP/Bank] Villager {} could not reach bank after max attempts, skipping",
                currentDepositor);
        currentDepositor = null;
        activeGoal = null;
    }

    /**
     * Schedules {@code goal} for removal from its villager's goal selector on the next bank tick.
     * Called by {@link BankDepositGoal#stop()} to avoid {@link java.util.ConcurrentModificationException}
     * inside the goal-scheduler iteration loop.
     */
    public void scheduleGoalRemoval(BankDepositGoal goal) {
        pendingGoalRemovals.add(goal);
    }

    // Private queue helpers

    /** Polls the next UUID from the queue and tries to attach a goal to that villager. */
    private void advanceToNext(ServerLevel level) {
        currentDepositor = depositQueue.poll();
        if (currentDepositor == null) return;
        queuedDepositors.remove(currentDepositor);
        tryAttachGoal(level, currentDepositor);
    }

    /**
     * Locates the villager entity for {@code uuid}, verifies they hold more than
     * {@link #MIN_EMERALDS_TO_DEPOSIT} emeralds,
     * and adds a new {@link BankDepositGoal} to their goal selector.
     * If the villager cannot be found or has insufficient emeralds, the slot is skipped.
     */
    private void tryAttachGoal(ServerLevel level, UUID uuid) {
        Villager villager = findVillagerEntity(level, uuid);
        if (villager == null) {
            // Villager not loaded or removed: skip
            currentDepositor = null;
            return;
        }
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.refreshInventoryCounts(villager.getInventory());
        if (stats.getCachedEmeraldCount() <= MIN_EMERALDS_TO_DEPOSIT) {
            // Villager no longer eligible (spent or traded emeralds): skip silently
            currentDepositor = null;
            return;
        }
        BankDepositGoal goal = new BankDepositGoal(villager, getBlockPos(), this);
        villager.goalSelector.addGoal(2, goal);
        activeGoal = goal;
    }

    /** Credits the villager's emerald inventory and distributes it to linked chests. */
    public void handleDepositorArrival(ServerLevel level, Villager villager) {
        UUID uuid = villager.getUUID();

        SimpleContainer inv = villager.getInventory();
        int totalEmeraldValue = EmeraldConsolidationUtils.countEmeraldValue(inv);
        int storageCapacity = Math.min(totalEmeraldValue, getEmeraldStorageCapacity(level));
        if (storageCapacity <= 0) return; // nothing to deposit or no room

        int emeraldBlocks = EmeraldConsolidationUtils.countItem(inv, Items.EMERALD_BLOCK);
        int emeralds = EmeraldConsolidationUtils.countItem(inv, Items.EMERALD);

        // Prefer complete emerald blocks first so block-only inventories are not
        // left behind. The bank stores deposits as raw emerald value, so whole
        // blocks are converted into nine emeralds when they are deposited.
        int blocksToDeposit = Math.min(emeraldBlocks, storageCapacity / 9);
        int emeraldsToDeposit = Math.min(emeralds, storageCapacity - blocksToDeposit * 9);
        int toDeposit = blocksToDeposit * 9 + emeraldsToDeposit;
        if (toDeposit <= 0) return; // nothing to deposit

        EmeraldConsolidationUtils.removeItems(inv, Items.EMERALD_BLOCK, blocksToDeposit);
        EmeraldConsolidationUtils.removeItems(inv, Items.EMERALD, emeraldsToDeposit);

        // Credit balance
        BankAccountData.get(level).deposit(uuid, toDeposit);

        // Physical deposit into chests in registration order
        distributeEmeraldsToChests(level, toDeposit);

        EmeraldCapitalism.LOGGER.debug(
                "[ECAP/Bank] Villager {} deposited {} emeralds", uuid, toDeposit);
    }

    /**
     * Physically distributes {@code amount} emeralds into the linked chests in
     * registration order, filling each chest before moving to the next. Callers reserve
     * capacity before removing emeralds, so completed deposits remain physically backed.
     */
    private void distributeEmeraldsToChests(ServerLevel level, int amount) {
        int remaining = amount;
        for (BlockPos chestPos : cachedChestPositions) {
            if (remaining <= 0) break;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (be instanceof EmeraldChestBlockEntity chest) {
                remaining = addEmeraldsToChest(chest, remaining);
            }
        }
        if (remaining > 0) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP/Bank] {} emerald(s) could not fit in any linked chest, "
                            + "balance was credited but physical items were lost",
                    remaining);
        }
    }

    /** Returns the number of raw emeralds that can currently fit in linked chests. */
    private int getEmeraldStorageCapacity(ServerLevel level) {
        long capacity = 0;
        for (BlockPos chestPos : cachedChestPositions) {
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.isEmpty()) {
                    capacity += Items.EMERALD.getDefaultMaxStackSize();
                } else if (stack.is(Items.EMERALD)) {
                    capacity += Items.EMERALD.getDefaultMaxStackSize() - stack.getCount();
                }
                if (capacity >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return (int) capacity;
    }

    /**
     * Withdraws {@code amount} emeralds from the linked chests in registration order.
     * Raw {@link Items#EMERALD} stacks are taken first; {@link Items#EMERALD_BLOCK} stacks
     * are broken as needed for any remaining shortfall, with surplus returned as raw emeralds.
     *
     * @param level  the server level (used to resolve block entities)
     * @param amount the number of emeralds to withdraw
     * @return {@code true} if the full amount was satisfied; {@code false} if the chests
     *         collectively held less than {@code amount} (partial withdrawal may have occurred)
     */
    public boolean withdrawFromLinkedChests(ServerLevel level, int amount) {
        int remaining = amount;
        Map<BlockPos, Map<Integer, ItemStack>> originalSlots = new HashMap<>();
        for (BlockPos chestPos : cachedChestPositions) {
            if (remaining <= 0) break;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (be instanceof EmeraldChestBlockEntity chest) {
                remaining = withdrawEmeraldsFromChest(chest, remaining, chestPos, originalSlots);
            }
        }
        if (remaining > 0) {
            restoreChestSlots(level, originalSlots);
            return false;
        }
        return true;
    }

    /**
     * Removes up to {@code needed} emeralds from a single chest.
     * Pass 1 takes raw emerald items; pass 2 breaks emerald blocks (9 each),
     * returning any fractional surplus as raw emeralds via {@link #addEmeraldsToChest}.
     *
     * @return the number of emeralds still needed after this chest (0 if fully satisfied)
     */
    private static int withdrawEmeraldsFromChest(EmeraldChestBlockEntity chest, int needed,
                                                  BlockPos chestPos,
                                                  Map<BlockPos, Map<Integer, ItemStack>> originalSlots) {
        int remaining = needed;
        int size = chest.getContainerSize();

        // Pass 1: raw emerald items
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.is(Items.EMERALD)) continue;
            int take = Math.min(stack.getCount(), remaining);
            rememberChestSlot(originalSlots, chestPos, slot, stack);
            stack.shrink(take);
            chest.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            remaining -= take;
        }

        // Pass 2: emerald blocks (9 emeralds each)
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.is(Items.EMERALD_BLOCK)) continue;
            int blocksNeeded = (remaining + 8) / 9; // ceiling division
            int take = Math.min(stack.getCount(), blocksNeeded);
            int emeraldsProvided = take * 9;
            rememberChestSlot(originalSlots, chestPos, slot, stack);
            stack.shrink(take);
            chest.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            int surplus = emeraldsProvided - remaining;
            remaining = 0;
            if (surplus > 0) {
                // Return the fractional surplus as raw emeralds
                addEmeraldsToChest(chest, surplus, chestPos, originalSlots);
            }
            break; // remaining is now 0
        }

        return remaining;
    }

    /**
     * Tries to insert up to {@code amount} emeralds into a chest, preferring to
     * top up existing partial stacks before using empty slots.
     *
     * @return the number of emeralds that did NOT fit
     */
    private static int addEmeraldsToChest(EmeraldChestBlockEntity chest, int amount) {
        return addEmeraldsToChest(chest, amount, null, null);
    }

    private static int addEmeraldsToChest(EmeraldChestBlockEntity chest, int amount,
                                          @Nullable BlockPos chestPos,
                                          @Nullable Map<BlockPos, Map<Integer, ItemStack>> originalSlots) {
        int remaining = amount;
        int size = chest.getContainerSize();

        // Pass 1: top up existing partial stacks
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack existing = chest.getItem(slot);
            if (existing.is(Items.EMERALD) && existing.getCount() < 64) {
                int canAdd = 64 - existing.getCount();
                int adding = Math.min(canAdd, remaining);
                rememberChestSlot(originalSlots, chestPos, slot, existing);
                existing.grow(adding);
                chest.setItem(slot, existing);
                remaining -= adding;
            }
        }

        // Pass 2: use empty slots
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            if (chest.getItem(slot).isEmpty()) {
                int stackSize = Math.min(remaining, 64);
                rememberChestSlot(originalSlots, chestPos, slot, ItemStack.EMPTY);
                chest.setItem(slot, new ItemStack(Items.EMERALD, stackSize));
                remaining -= stackSize;
            }
        }

        return remaining;
    }

    private static void rememberChestSlot(Map<BlockPos, Map<Integer, ItemStack>> originalSlots,
                                          BlockPos chestPos, int slot, ItemStack original) {
        if (originalSlots == null || chestPos == null) {
            return;
        }
        originalSlots.computeIfAbsent(chestPos.immutable(), ignored -> new HashMap<>())
                .putIfAbsent(slot, original.copy());
    }

    private static void restoreChestSlots(ServerLevel level,
                                          Map<BlockPos, Map<Integer, ItemStack>> originalSlots) {
        for (Map.Entry<BlockPos, Map<Integer, ItemStack>> chestEntry : originalSlots.entrySet()) {
            if (!(level.getBlockEntity(chestEntry.getKey()) instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (Map.Entry<Integer, ItemStack> slotEntry : chestEntry.getValue().entrySet()) {
                chest.setItem(slotEntry.getKey(), slotEntry.getValue().copy());
            }
        }
    }

    /**
     * Extracts up to {@code maxAmount} matching items from linked Emerald Chests.
     * The live chest contents are checked at extraction time; cached totals are only
     * used by callers to decide whether an attempt is worthwhile.
     */
    public ItemStack withdrawItem(ServerLevel level, Item item, int maxAmount) {
        return withdrawMatchingItem(level, stack -> stack.is(item), maxAmount);
    }

    /**
     * Withdraws exactly {@code amount} matching items, or nothing if the linked chests
     * do not contain the complete amount. This avoids partially consuming construction
     * materials when a smith's precondition becomes stale between ticks.
     */
    public ItemStack withdrawExactItem(ServerLevel level, Item item, int amount) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }

        if (item == ECAPItems.EMERALD_CHEST.get()) {
            return withdrawEmeraldChestItems(level, amount);
        }

        if (isLogMarketItem(item)) {
            return withdrawExactMatchingItem(level, item, amount, stack -> stack.is(ItemTags.LOGS));
        }
        if (isCoalMarketItem(item)) {
            return withdrawExactMatchingItem(level, item, amount, stack -> stack.is(ItemTags.COALS));
        }

        int available = 0;
        for (BlockPos chestPos : cachedChestPositions) {
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.is(item)) {
                    available += stack.getCount();
                    if (available >= amount) {
                        break;
                    }
                }
            }
            if (available >= amount) {
                break;
            }
        }

        if (available < amount) {
            return ItemStack.EMPTY;
        }

        int remaining = amount;
        for (BlockPos chestPos : cachedChestPositions) {
            if (remaining <= 0) {
                break;
            }
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                ItemStack stored = chest.getItem(slot);
                if (!stored.is(item)) {
                    continue;
                }
                int taken = Math.min(stored.getCount(), remaining);
                openChestForTransfer(level, chestPos);
                stored.shrink(taken);
                chest.setItem(slot, stored.isEmpty() ? ItemStack.EMPTY : stored);
                remaining -= taken;
            }
        }

        if (remaining != 0) {
            return ItemStack.EMPTY;
        }

        refreshInventoryTotals(level);
        setChanged();
        return new ItemStack(item, amount);
    }

    /** Withdraws ordinary stocked Emerald Chest items without touching repair reserves. */
    private ItemStack withdrawEmeraldChestItems(ServerLevel level, int amount) {
        int available = 0;
        for (BlockPos chestPos : cachedChestPositions) {
            if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.is(ECAPItems.EMERALD_CHEST.get())) {
                    available += stack.getCount();
                    if (available >= amount) {
                        break;
                    }
                }
            }
            if (available >= amount) {
                break;
            }
        }
        if (available < amount) {
            return ItemStack.EMPTY;
        }

        ItemStack withdrawn = withdrawExactMatchingItem(level, ECAPItems.EMERALD_CHEST.get(), amount,
                stack -> stack.is(ECAPItems.EMERALD_CHEST.get()));
        if (withdrawn.isEmpty()) {
            return ItemStack.EMPTY;
        }
        setChanged();
        return withdrawn;
    }

    /**
     * Withdraws an exact number of plank-equivalents, returning them as oak
     * planks. Stored planks count one-for-one and stored logs count four each.
     * Any final log remainder is intentionally consumed as part of the bank's
     * conversion reserve; callers are charged only for the requested planks.
     */
    public ItemStack withdrawExactPlanks(ServerLevel level, int amount) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }

        int available = 0;
        for (BlockPos chestPos : cachedChestPositions) {
            if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.is(ItemTags.PLANKS)) {
                    available = Math.min(Integer.MAX_VALUE, available + stack.getCount());
                } else if (stack.is(ItemTags.LOGS)) {
                    available = (int) Math.min(Integer.MAX_VALUE,
                            (long) available + (long) stack.getCount() * 4L);
                }
                if (available >= amount) {
                    break;
                }
            }
            if (available >= amount) {
                break;
            }
        }
        if (available < amount) {
            return ItemStack.EMPTY;
        }

        int remaining = amount;
        for (BlockPos chestPos : cachedChestPositions) {
            if (remaining <= 0) {
                break;
            }
            if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                ItemStack stored = chest.getItem(slot);
                if (stored.is(ItemTags.PLANKS)) {
                    int taken = Math.min(stored.getCount(), remaining);
                    openChestForTransfer(level, chestPos);
                    stored.shrink(taken);
                    chest.setItem(slot, stored.isEmpty() ? ItemStack.EMPTY : stored);
                    remaining -= taken;
                } else if (stored.is(ItemTags.LOGS)) {
                    int logs = Math.min(stored.getCount(), (remaining + 3) / 4);
                    openChestForTransfer(level, chestPos);
                    stored.shrink(logs);
                    chest.setItem(slot, stored.isEmpty() ? ItemStack.EMPTY : stored);
                    remaining -= Math.min(remaining, logs * 4);
                }
            }
        }
        if (remaining > 0) {
            return ItemStack.EMPTY;
        }

        refreshInventoryTotals(level);
        setChanged();
        return new ItemStack(Items.OAK_PLANKS, amount);
    }

    private ItemStack withdrawExactMatchingItem(ServerLevel level, Item resultItem, int amount,
                                                Predicate<ItemStack> matches) {
        int available = 0;
        for (BlockPos chestPos : cachedChestPositions) {
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof EmeraldChestBlockEntity chest)) continue;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (matches.test(stack)) {
                    available += stack.getCount();
                    if (available >= amount) break;
                }
            }
            if (available >= amount) break;
        }
        if (available < amount) {
            return ItemStack.EMPTY;
        }

        int remaining = amount;
        for (BlockPos chestPos : cachedChestPositions) {
            if (remaining <= 0) break;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof EmeraldChestBlockEntity chest)) continue;
            for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                ItemStack stored = chest.getItem(slot);
                if (!matches.test(stored)) continue;
                int taken = Math.min(stored.getCount(), remaining);
                openChestForTransfer(level, chestPos);
                stored.shrink(taken);
                chest.setItem(slot, stored.isEmpty() ? ItemStack.EMPTY : stored);
                remaining -= taken;
            }
        }
        if (remaining != 0) {
            return ItemStack.EMPTY;
        }
        refreshInventoryTotals(level);
        setChanged();
        return new ItemStack(resultItem, amount);
    }

    /** Extracts emerald ore of either the regular or deepslate variant. */
    public ItemStack withdrawEmeraldOre(ServerLevel level, int maxAmount) {
        ItemStack regularOre = withdrawItem(level, Items.EMERALD_ORE, maxAmount);
        return regularOre.isEmpty()
                ? withdrawItem(level, Items.DEEPSLATE_EMERALD_ORE, maxAmount)
                : regularOre;
    }

    private ItemStack withdrawMatchingItem(ServerLevel level, Predicate<ItemStack> matches, int maxAmount) {
        if (maxAmount <= 0) return ItemStack.EMPTY;

        int remaining = maxAmount;
        ItemStack extracted = ItemStack.EMPTY;
        for (BlockPos chestPos : cachedChestPositions) {
            if (remaining <= 0) break;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof EmeraldChestBlockEntity chest)) continue;

            for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                ItemStack stored = chest.getItem(slot);
                if (stored.isEmpty() || !matches.test(stored)) continue;

                int taken = Math.min(stored.getCount(), remaining);
                openChestForTransfer(level, chestPos);
                if (extracted.isEmpty()) {
                    extracted = stored.copyWithCount(taken);
                } else {
                    extracted.grow(taken);
                }
                stored.shrink(taken);
                chest.setItem(slot, stored);
                remaining -= taken;
            }
        }

        if (!extracted.isEmpty()) {
            refreshInventoryTotals(level);
            setChanged();
        }
        return extracted;
    }

    /**
     * Stores the complete stack in linked Emerald Chests. The method first checks
     * aggregate live capacity, so a failed transfer does not partially consume the
     * processor's output stack.
     */
    public boolean storeItemInLinkedChests(ServerLevel level, ItemStack source) {
        if (source.isEmpty() || getItemStorageCapacity(level, source) < source.getCount()) {
            return false;
        }

        int remaining = source.getCount();
        Map<BlockPos, Map<Integer, ItemStack>> originalSlots = new HashMap<>();
        for (BlockPos chestPos : cachedChestPositions) {
            if (remaining <= 0) break;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof EmeraldChestBlockEntity chest)) continue;

            for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                ItemStack stored = chest.getItem(slot);
                if (stored.isEmpty() || !ItemStack.isSameItemSameComponents(stored, source)) continue;
                int canAdd = stored.getMaxStackSize() - stored.getCount();
                int added = Math.min(canAdd, remaining);
                if (added > 0) {
                    rememberChestSlot(originalSlots, chestPos, slot, stored);
                    openChestForTransfer(level, chestPos);
                    stored.grow(added);
                    chest.setItem(slot, stored);
                    remaining -= added;
                }
            }

            for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                if (!chest.getItem(slot).isEmpty()) continue;
                int added = Math.min(source.getMaxStackSize(), remaining);
                rememberChestSlot(originalSlots, chestPos, slot, ItemStack.EMPTY);
                openChestForTransfer(level, chestPos);
                chest.setItem(slot, source.copyWithCount(added));
                remaining -= added;
            }
        }

        if (remaining != 0) {
            // Capacity was preflighted, so this indicates a changed/stale chest set.
            restoreChestSlots(level, originalSlots);
            return false;
        }
        source.setCount(0);
        refreshInventoryTotals(level);
        setChanged();
        return true;
    }

    /** Returns the live number of items that can be added for this stack type. */
    public int getItemStorageCapacity(ServerLevel level, ItemStack template) {
        int capacity = 0;
        for (BlockPos chestPos : cachedChestPositions) {
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof EmeraldChestBlockEntity chest)) continue;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stored = chest.getItem(slot);
                if (stored.isEmpty()) {
                    capacity += template.getMaxStackSize();
                } else if (ItemStack.isSameItemSameComponents(stored, template)) {
                    capacity += stored.getMaxStackSize() - stored.getCount();
                }
                if (capacity >= template.getCount()) return capacity;
            }
        }
        return capacity;
    }

    private void openChestForTransfer(ServerLevel level, BlockPos chestPos) {
        BlockState state = level.getBlockState(chestPos);
        level.blockEvent(chestPos, state.getBlock(), 1, 1);
        transferChestCloseTicks.merge(chestPos.immutable(), level.getGameTime() + 10L, Math::max);
    }

    private void tickTransferChestAnimations(ServerLevel level, long gameTime) {
        transferChestCloseTicks.entrySet().removeIf(entry -> {
            if (gameTime < entry.getValue()) return false;
            BlockPos chestPos = entry.getKey();
            BlockState state = level.getBlockState(chestPos);
            if (state.is(ECAPBlocks.EMERALD_CHEST.get())) {
                level.blockEvent(chestPos, state.getBlock(), 1, 0);
            }
            return true;
        });
    }

    /** Refreshes cached Bank inventory totals after a server-side inventory mutation. */
    public void markInventoryChanged(ServerLevel level) {
        refreshInventoryTotals(level);
        setChanged();
    }

    // Employee tracking

    /**
     * Pings the closest loaded Bank in the villager's chunk or an adjacent chunk.
     * World-generated villagers use this one-shot path to register with the Bank
     * that contains them without making every Bank scan its surrounding entities.
     */
    public static boolean registerSpawnedEmployeeAtNearestBank(ServerLevel level, Villager villager) {
        ChunkPos villagerChunk = new ChunkPos(villager.blockPosition());
        BankBlockEntity nearestBank = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int chunkX = villagerChunk.x - 1; chunkX <= villagerChunk.x + 1; chunkX++) {
            for (int chunkZ = villagerChunk.z - 1; chunkZ <= villagerChunk.z + 1; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof BankBlockEntity bank) || bank.isRemoved()
                            || !bank.getSearchBox().contains(villager.getX(), villager.getY(), villager.getZ())) {
                        continue;
                    }
                    double distance = bank.getBlockPos().distSqr(villager.blockPosition());
                    if (distance < nearestDistance) {
                        nearestBank = bank;
                        nearestDistance = distance;
                    }
                }
            }
        }

        return nearestBank != null && nearestBank.registerSpawnedEmployee(villager);
    }

    /** Returns the UUIDs of this Bank's employees in registration order. */
    public List<UUID> getEmployeeIds() {
        return List.copyOf(employeeIds);
    }

    public int getEmployeeCount() {
        return employeeIds.size();
    }

    /** Returns whether this Bank has no registered employees. */
    public boolean hasNoEmployees() {
        return employeeIds.isEmpty();
    }

    /**
     * Returns whether a live vault guard remains in this Bank's vault area.
     * Emerald guards carry an explicit Bank position; all generated guards also
     * carry the serialized vault-guard attachment.
     */
    public boolean hasLivingVaultGolems(ServerLevel level) {
        AABB searchArea = new AABB(worldPosition)
                .inflate(SEARCH_RADIUS, SEARCH_RADIUS * 2, SEARCH_RADIUS);
        return !level.getEntitiesOfClass(IronGolem.class, searchArea,
                golem -> golem.isAlive() && isVaultGolem(golem)).isEmpty();
    }

    private boolean isVaultGolem(IronGolem golem) {
        return VaultGolemGoals.isVaultGuard(golem)
                || golem instanceof EmeraldGolem emeraldGolem
                && (worldPosition.equals(emeraldGolem.getBankEmployeePos())
                || emeraldGolemEmployeeIds.contains(golem.getUUID()));
    }

    /** Returns whether the employee and vault-guard conditions allow takeover. */
    public boolean meetsTakeoverRequirements(ServerLevel level) {
        return hasNoEmployees() && !hasLivingVaultGolems(level);
    }

    /** Records the player who directly killed the final Bank employee. */
    public boolean recordLastEmployeeDirectKiller(UUID employeeId, UUID playerId, long currentTick) {
        if (playerId == null || employeeId == null || employeeIds.size() != 1
                || !employeeIds.contains(employeeId)) {
            return false;
        }
        takeoverLockPlayer = playerId;
        takeoverLockUntil = currentTick + DIRECT_KILL_TAKEOVER_LOCK_TICKS;
        setChanged();
        return true;
    }

    /** Returns whether the bank is temporarily reserved for a different killer. */
    public boolean isTakeoverLocked(ServerLevel level) {
        if (takeoverLockPlayer == null) {
            return false;
        }
        if (level.getGameTime() >= takeoverLockUntil) {
            takeoverLockPlayer = null;
            takeoverLockUntil = 0L;
            setChanged();
            return false;
        }
        return true;
    }

    /** Server-authoritative takeover check used by the control packet. */
    public boolean canPlayerTakeControl(ServerLevel level, UUID playerId) {
        if (!isBankIndependent() || !meetsTakeoverRequirements(level)) {
            return false;
        }
        if (!isTakeoverLocked(level)) {
            return true;
        }
        return playerId != null && playerId.equals(takeoverLockPlayer);
    }

    /** Returns the target emerald-golem count based on physical chest reserves. */
    public int getExpectedEmeraldGolemCount() {
        return EmeraldGolemCalculator.calculate(totalEmeraldCount);
    }

    /** Returns the internal pumpkin stock target from at least one expected golem. */
    public int getPumpkinTarget() {
        return BankTargets.pumpkinTarget(Math.max(1, getExpectedEmeraldGolemCount()));
    }

    /** Returns the internal bread stock target: five days of bread per registered village villager. */
    public int getBreadTarget() {
        Level level = getLevel();
        if (!(level instanceof ServerLevel serverLevel) || villageId == null) {
            return 0;
        }

        VillageRecord village = VillageRegistryData.get(serverLevel).getVillages().get(villageId);
        return village == null ? 0 : BankTargets.breadTarget(village.getMembers().size());
    }

    /** Returns the coal-and-charcoal reserve target for this bank. */
    public int getCoalTarget() {
        return BankTargets.coalTarget(totalEmeraldOreCount);
    }

    /** Returns the plank-equivalent target for this bank's village. */
    public int getPlankTarget() {
        Level level = getLevel();
        if (!(level instanceof ServerLevel serverLevel) || villageId == null) {
            return BankTargets.plankTarget(Math.max(1, getExpectedEmeraldGolemCount()),
                    getChestCount(), 0, 0);
        }

        ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
        VillageRecord village = overworld == null
                ? null
                : VillageRegistryData.get(overworld).getVillages().get(villageId);
        int beds = village == null ? 0 : village.countBeds(overworld)[0];
        int doors = village == null ? 0
                : village.getDoorRegistry().size() + village.getMissingDoorRegistry().size();
        return BankTargets.plankTarget(Math.max(1, getExpectedEmeraldGolemCount()),
                getChestCount(), beds, doors);
    }

    /** Returns the number of live emerald golems in this bank's associated village. */
    public int getEmeraldGolemCount() {
        Level level = getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }

        AABB searchArea = new AABB(worldPosition).inflate(SEARCH_RADIUS, SEARCH_RADIUS * 2, SEARCH_RADIUS);
        if (villageId != null) {
            VillageRecord record = VillageRegistryData.get(serverLevel).getVillages().get(villageId);
            if (record != null) {
                searchArea = record.getBoundingBox();
            }
        }

        return serverLevel.getEntitiesOfClass(EmeraldGolem.class, searchArea,
                golem -> golem.isAlive() && !(golem instanceof EmeraldSkrimisher)).size();
    }

    /** Returns the number of live Emerald Skrimishers in this bank's associated village. */
    public int getEmeraldSkrimisherCount(ServerLevel level) {
        AABB searchArea = new AABB(worldPosition).inflate(SEARCH_RADIUS, SEARCH_RADIUS * 2, SEARCH_RADIUS);
        if (villageId != null) {
            VillageRecord record = VillageRegistryData.get(level).getVillages().get(villageId);
            if (record != null) {
                searchArea = record.getBoundingBox();
            }
        }
        return level.getEntitiesOfClass(EmeraldSkrimisher.class, searchArea, EmeraldSkrimisher::isAlive).size();
    }

    /** Returns the skirmisher limit, which is twice the live emerald-golem count. */
    public int getEmeraldSkrimisherLimit(ServerLevel level) {
        return BankTargets.emeraldSkrimisherLimit(getEmeraldGolemCount());
    }

    /** Returns whether a linked chest currently contains at least one matching item. */
    public boolean hasStoredItem(ServerLevel level, Item item) {
        for (BlockPos chestPos : cachedChestPositions) {
            if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).is(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isEmployee(UUID villagerId) {
        return employeeIds.contains(villagerId);
    }

    /** Records an Emerald Golem spawned as an employee of this Bank. */
    public boolean registerEmeraldGolemEmployee(UUID golemId) {
        if (!emeraldGolemEmployeeIds.add(golemId)) {
            return false;
        }
        setChanged();
        return true;
    }

    /** Removes a vault guard after its entity dies. */
    public boolean removeEmeraldGolemEmployee(UUID golemId) {
        if (!emeraldGolemEmployeeIds.remove(golemId)) {
            return false;
        }
        setChanged();
        return true;
    }

    /** Returns the UUIDs of Emerald Golem employees in registration order. */
    public List<UUID> getEmeraldGolemEmployeeIds() {
        return List.copyOf(emeraldGolemEmployeeIds);
    }

    @Nullable
    public BlockPos getComposterPos() {
        return composterPos;
    }

    /**
     * Registers a villager that spawned in the Bank's immediate area. The cap is
     * enforced here so event-driven and scan-driven registration share one rule.
     */
    public boolean registerSpawnedEmployee(Villager villager) {
        if (!getSearchBox().contains(villager.getX(), villager.getY(), villager.getZ())) {
            return false;
        }
        boolean added = addEmployee(villager.getUUID(), false);
        if (added) {
            setChanged();
        }
        return added;
    }

    /**
     * Registers job-site employees and learns the Bank's composter. The caller
     * supplies the villager's server-side JOB_SITE memory; the live block state is
     * checked so stale memories cannot assign an unrelated block to this Bank.
     */
    public boolean registerEmployeeFromJob(ServerLevel level, Villager villager, @Nullable BlockPos jobSitePos) {
        if (jobSitePos == null) {
            return false;
        }

        BlockState jobState = level.getBlockState(jobSitePos);
        UUID villagerId = villager.getUUID();
        boolean changed = false;

        if (jobSitePos.equals(worldPosition) && jobState.is(ECAPBlocks.BANK.get())) {
            changed |= addEmployee(villagerId, true);
        } else if (isOwnedProcessor(jobSitePos, jobState)) {
            changed |= addEmployee(villagerId, true);
        } else if (jobState.is(Blocks.COMPOSTER)) {
            // The first composter is learned only from an existing employee. Once
            // learned, another villager claiming that exact block is an employee too.
            boolean isKnownComposter = composterPos != null && composterPos.equals(jobSitePos);
            if (employeeIds.contains(villagerId) || isKnownComposter) {
                changed |= addEmployee(villagerId, true);
                if (!jobSitePos.equals(composterPos)) {
                    composterPos = jobSitePos.immutable();
                    changed = true;
                }
            }
        }

        if (changed) {
            setChanged();
        }
        return changed;
    }

    /** Removes an employee after the corresponding villager has died. */
    public boolean removeEmployee(UUID villagerId) {
        if (!employeeIds.remove(villagerId)) {
            return false;
        }
        jobEmployeeIds.remove(villagerId);
        setChanged();
        return true;
    }

    private boolean addEmployee(UUID villagerId, boolean jobBacked) {
        if (employeeIds.contains(villagerId)) {
            return jobBacked && jobEmployeeIds.add(villagerId);
        }
        if (employeeIds.size() >= MAX_EMPLOYEES) {
            if (!jobBacked) {
                return false;
            }
            UUID replaceable = employeeIds.stream()
                    .filter(existing -> !jobEmployeeIds.contains(existing))
                    .findFirst()
                    .orElse(null);
            if (replaceable == null) {
                return false;
            }
            employeeIds.remove(replaceable);
        }
        employeeIds.add(villagerId);
        if (jobBacked) {
            jobEmployeeIds.add(villagerId);
        }
        return true;
    }

    private boolean isOwnedProcessor(BlockPos jobSitePos, BlockState jobState) {
        if (!jobState.is(ECAPBlocks.EMERALD_ORE_PROCESSOR.get())) {
            return false;
        }
        if (closestEmeraldProcessorPos != null && !processorCacheDirty) {
            return closestEmeraldProcessorPos.equals(jobSitePos);
        }
        // A newly placed processor can be claimed before the next Bank scan. The
        // same search volume used by the cache is a safe fallback in that window.
        return getSearchBox().contains(jobSitePos.getX() + 0.5, jobSitePos.getY() + 0.5,
                jobSitePos.getZ() + 0.5);
    }

    /**
     * Finds a villager by UUID within the current village's bounding box, or within a
     * generous radius around the bank if the village record is unavailable.
     */
    @Nullable
    private Villager findVillagerEntity(ServerLevel level, UUID uuid) {
        Entity entity = level.getEntity(uuid);
        return entity instanceof Villager villager ? villager : null;
    }

    // Scanning

    private void fullScan(ServerLevel level) {
        unlinkCachedChests(level);
        cachedChestPositions.clear();
        cachedChestTotals.clear();
        BlockPos closestProcessor = null;
        double closestProcessorDistance = Double.MAX_VALUE;

        AABB searchBox = getSearchBox();
        int minX = (int) Math.floor(searchBox.minX);
        int minY = (int) Math.floor(searchBox.minY);
        int minZ = (int) Math.floor(searchBox.minZ);
        int maxX = (int) Math.floor(searchBox.maxX);
        int maxY = (int) Math.floor(searchBox.maxY);
        int maxZ = (int) Math.floor(searchBox.maxZ);

        boolean trackedLocationsChanged = false;
        for (BlockPos candidate : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockState candidateState = level.getBlockState(candidate);
            if (candidateState.is(ECAPBlocks.EMERALD_CHEST.get())) {
                BlockEntity blockEntity = level.getBlockEntity(candidate);
                if (blockEntity instanceof EmeraldChestBlockEntity chest) {
                    BlockPos immutablePos = candidate.immutable();
                    cachedChestPositions.add(immutablePos);
                    cachedChestTotals.put(immutablePos, chest.getStoredCounts());
                    if (trackedChestPositions.size() < MAX_TRACKED_CHEST_LOCATIONS) {
                        trackedLocationsChanged |= trackedChestPositions.add(immutablePos);
                    }
                    chest.linkBank(worldPosition);
                }
            }
            if (candidateState.is(ECAPBlocks.EMERALD_ORE_PROCESSOR.get())) {
                double distance = candidate.distSqr(worldPosition);
                if (distance < closestProcessorDistance) {
                    closestProcessor = candidate.immutable();
                    closestProcessorDistance = distance;
                }
            }
        }
        closestEmeraldProcessorPos = closestProcessor;

        refreshInventoryTotals(level);
        if (trackedLocationsChanged) {
            setChanged();
        }

        // Re-populate the deposit queue so newly eligible villagers don't have to wait
        // for the next VM periodic scan (which runs far less frequently).
        repopulateDepositQueue(level);
    }

    /**
     * Scans all registered village members currently in the world and enqueues any who
     * hold more than {@link #MIN_EMERALDS_TO_DEPOSIT} emeralds and are not already
     * in the deposit queue.
     * Called after every full chest scan.
     */
    private void repopulateDepositQueue(ServerLevel level) {
        if (villageId == null) return;
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) return;
        VillageRecord record = VillageRegistryData.get(overworld).getVillages().get(villageId);
        if (record == null) return;

        AABB bounds = record.getBoundingBox();
        for (UUID uuid : record.getMembers().keySet()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof Villager villager
                    && bounds.intersects(villager.getBoundingBox())) {
                queueDepositIfEligible(villager);
            }
        }
    }

    private void verifyCachedChests(ServerLevel level) {
        boolean removed = cachedChestPositions.removeIf(pos -> {
            if (!level.isLoaded(pos) || level.getBlockState(pos).is(ECAPBlocks.EMERALD_CHEST.get())) {
                return false;
            }
            cachedChestTotals.remove(pos);
            return true;
        });
        if (removed) {
            refreshInventoryTotals(level);
        }
        if (composterPos != null && level.hasChunk(composterPos.getX() >> 4, composterPos.getZ() >> 4)
                && !level.getBlockState(composterPos).is(Blocks.COMPOSTER)) {
            composterPos = null;
            setChanged();
        }
        if (closestEmeraldProcessorPos != null
                && !level.getBlockState(closestEmeraldProcessorPos).is(ECAPBlocks.EMERALD_ORE_PROCESSOR.get())) {
            closestEmeraldProcessorPos = null;
            processorCacheDirty = true;
        }
        if (removed) {
            setChanged();
        }
        // Processor slots are live inventory rather than event-driven snapshots.
        // Refresh here so completed processing and fuel/input changes reach the bank
        // resource totals without waiting for the next full-area scan.
        refreshInventoryTotals(level);
    }

    /** Aggregates cached chest totals and the nearest processor's live inventory. */
    private void refreshInventoryTotals(ServerLevel level) {
        InventoryTotals totals = new InventoryTotals();
        for (EmeraldChestBlockEntity.StoredCounts counts : cachedChestTotals.values()) {
            totals.add(counts);
        }
        if (closestEmeraldProcessorPos != null
                && level.getBlockEntity(closestEmeraldProcessorPos)
                instanceof EmeraldOreProcessorBlockEntity processor) {
            for (int slot = 0; slot < processor.getContainerSize(); slot++) {
                totals.add(processor.getItem(slot));
            }
        }
        if (totals.emeraldValue != totalEmeraldCount || totals.emeraldBlocks != totalEmeraldBlockCount
                || totals.emeraldOre != totalEmeraldOreCount
                || totals.pumpkins != totalPumpkinCount || totals.wheat != totalWheatCount
                || totals.bread != totalBreadCount || totals.coal != totalCoalCount
                || totals.emeraldGreenDye != totalEmeraldGreenDyeCount
                || totals.plankEquivalent != totalPlankCount) {
            totalEmeraldCount = totals.emeraldValue;
            totalEmeraldBlockCount = totals.emeraldBlocks;
            totalEmeraldOreCount = totals.emeraldOre;
            totalPumpkinCount = totals.pumpkins;
            totalWheatCount = totals.wheat;
            totalBreadCount = totals.bread;
            totalCoalCount = totals.coal;
            totalEmeraldGreenDyeCount = totals.emeraldGreenDye;
            totalPlankCount = totals.plankEquivalent;
            setChanged();
        }
    }

    /** Mutable accumulator used to combine chest snapshots with processor slots. */
    private static final class InventoryTotals {
        private int emeraldValue;
        private int emeraldBlocks;
        private int emeraldOre;
        private int pumpkins;
        private int wheat;
        private int bread;
        private int coal;
        private int emeraldGreenDye;
        private int plankEquivalent;

        private void add(EmeraldChestBlockEntity.StoredCounts counts) {
            emeraldValue += counts.emeraldValue();
            emeraldBlocks += counts.emeraldBlocks();
            emeraldOre += counts.emeraldOre();
            pumpkins += counts.pumpkins();
            wheat += counts.wheat();
            bread += counts.bread();
            coal += counts.coal();
            emeraldGreenDye += counts.emeraldGreenDye();
            plankEquivalent += counts.plankEquivalent();
        }

        private void add(ItemStack stack) {
            int count = stack.getCount();
            if (stack.is(Items.EMERALD)) {
                emeraldValue += count;
            } else if (stack.is(Items.EMERALD_BLOCK)) {
                emeraldBlocks += count;
                emeraldValue += count * 9;
            } else if (stack.is(Items.EMERALD_ORE) || stack.is(Items.DEEPSLATE_EMERALD_ORE)) {
                emeraldOre += count;
            }
            if (stack.is(Items.PUMPKIN)) pumpkins += count;
            if (stack.is(Items.WHEAT)) wheat += count;
            if (stack.is(Items.BREAD)) bread += count;
            if (stack.is(ItemTags.COALS)) coal += count;
            if (stack.is(ECAPItems.EMERALD_GREEN_DYE.get())) emeraldGreenDye += count;
            if (stack.is(ItemTags.PLANKS)) plankEquivalent += count;
            if (stack.is(ItemTags.LOGS)) plankEquivalent += count * 4;
        }
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

    /** Returns true when no player controls this bank. */
    public boolean isBankIndependent() {
        return bankIndependent;
    }

    @Nullable
    public UUID getControllerId() {
        return controllerId;
    }

    public boolean isControlledBy(UUID playerId) {
        return !bankIndependent && playerId != null && playerId.equals(controllerId);
    }

    /** Gives control to one player and switches the bank out of independent mode. */
    public boolean setController(@Nullable UUID playerId) {
        if (playerId == null) {
            if (bankIndependent && controllerId == null) {
                return false;
            }
            bankIndependent = true;
            controllerId = null;
        } else {
            if (!bankIndependent && playerId.equals(controllerId)) {
                return false;
            }
            bankIndependent = false;
            controllerId = playerId;
        }
        setChanged();
        return true;
    }

    public void autoDetectVillage() {
        Level level = getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) return;
        VillageRecord record = VillageRegistryData.get(overworld).getVillageFor(getBlockPos());
        if (record != null) {
            setVillageId(record.getVillageId());
        }
    }

    /** Backfills the direct bank link for worlds saved before that registry existed. */
    @Override
    public void onLoad() {
        super.onLoad();
        Level level = getLevel();
        if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
            return;
        }
        LOADED_BANKS.computeIfAbsent(serverLevel,
                ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(this);
        if (villageId == null) {
            return;
        }
        VillageRegistryData data = VillageRegistryData.get(serverLevel);
        if (data.getBankPos(villageId) == null) {
            data.registerBankPosition(villageId, getBlockPos());
        }
    }

    // Public accessors

    /** Returns an unmodifiable, insertion-ordered view of the cached emerald-chest positions. */
    public Set<BlockPos> getCachedChestPositions() {
        return Collections.unmodifiableSet(cachedChestPositions);
    }

    public int getChestCount() {
        return cachedChestPositions.size();
    }

    /** Returns all durable emerald-chest locations, including currently missing ones. */
    public Set<BlockPos> getTrackedChestPositions() {
        return Collections.unmodifiableSet(trackedChestPositions);
    }

    /** Returns tracked locations that are loaded and no longer contain an Emerald Chest. */
    public List<BlockPos> getMissingChestPositions(ServerLevel level) {
        List<BlockPos> missing = new ArrayList<>();
        for (BlockPos chestPos : trackedChestPositions) {
            if (level.isLoaded(chestPos)
                    && !level.getBlockState(chestPos).is(ECAPBlocks.EMERALD_CHEST.get())) {
                missing.add(chestPos);
            }
        }
        return List.copyOf(missing);
    }

    public int getMissingChestCount(ServerLevel level) {
        return getMissingChestPositions(level).size();
    }

    /** Returns repair chests prepared by an Emeraldsmith and awaiting placement. */
    public int getPreparedEmeraldChestCount() {
        return preparedEmeraldChestCount;
    }

    /**
     * Returns stocked Emerald Chest items that are not needed for currently
     * missing tracked locations. Prepared repair chests are counted as the
     * first part of that replacement reserve.
     */
    public int getSurplusEmeraldChestCount(ServerLevel level) {
        int stocked = getMarketStock(level, ECAPItems.EMERALD_CHEST.get());
        int replacementNeed = Math.max(0,
                getMissingChestCount(level) - preparedEmeraldChestCount);
        return Math.max(0, stocked - replacementNeed);
    }

    /** Withdraws only Emerald Chest items that are surplus to the Bank's replacement need. */
    public ItemStack withdrawSurplusEmeraldChests(ServerLevel level, int maxAmount) {
        if (maxAmount <= 0) {
            return ItemStack.EMPTY;
        }
        int amount = Math.min(maxAmount, getSurplusEmeraldChestCount(level));
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        return withdrawExactMatchingItem(level, ECAPItems.EMERALD_CHEST.get(), amount,
                stack -> stack.is(ECAPItems.EMERALD_CHEST.get()));
    }

    /**
     * Crafts as many missing Emerald Chests as current bank materials allow.
     * Each chest costs eight emeralds and one vanilla chest, matching the normal
     * Emerald Chest recipe. The result is held as a durable bank repair reserve
     * until a banker places it at a tracked location.
     */
    public int craftMissingEmeraldChests(ServerLevel level) {
        int missing = Math.max(0, getMissingChestCount(level) - preparedEmeraldChestCount);
        if (missing == 0) {
            return 0;
        }

        int availableChests = getMarketStock(level, Items.CHEST);
        int availableEmeralds = getLiveEmeraldValue(level);
        int craftable = Math.min(missing, Math.min(availableChests,
                availableEmeralds / EmeraldOreProcessorBlockEntity.EMERALDS_PER_CHEST));
        if (craftable <= 0) {
            return 0;
        }

        ItemStack chestMaterials = withdrawExactItem(level, Items.CHEST, craftable);
        if (chestMaterials.isEmpty() || !withdrawFromLinkedChests(level,
                craftable * EmeraldOreProcessorBlockEntity.EMERALDS_PER_CHEST)) {
            if (!chestMaterials.isEmpty()) {
                storeItemInLinkedChests(level, chestMaterials);
            }
            return 0;
        }

        preparedEmeraldChestCount = Math.min(MAX_TRACKED_CHEST_LOCATIONS,
                preparedEmeraldChestCount + craftable);
        setChanged();
        return craftable;
    }

    /** Places one prepared Emerald Chest into the first safe tracked location. */
    public boolean replaceMissingEmeraldChest(ServerLevel level) {
        if (preparedEmeraldChestCount <= 0) {
            return false;
        }
        for (BlockPos chestPos : getMissingChestPositions(level)) {
            BlockState state = level.getBlockState(chestPos);
            if (!state.isAir() || !level.setBlock(chestPos,
                    ECAPBlocks.EMERALD_CHEST.get().defaultBlockState(), 3)) {
                continue;
            }
            if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                level.setBlock(chestPos, Blocks.AIR.defaultBlockState(), 3);
                continue;
            }
            BlockPos immutablePos = chestPos.immutable();
            cachedChestPositions.add(immutablePos);
            cachedChestTotals.put(immutablePos, chest.getStoredCounts());
            chest.linkBank(worldPosition);
            preparedEmeraldChestCount--;
            refreshInventoryTotals(level);
            setChanged();
            return true;
        }
        return false;
    }

    /** Returns the nearest Emerald Processor in the Bank's search cube, if one is cached. */
    @Nullable
    public BlockPos getClosestEmeraldProcessorPos() {
        return closestEmeraldProcessorPos;
    }

    public int getTotalEmeraldCount() {
        return totalEmeraldCount;
    }

    public int getTotalEmeraldBlockCount() {
        return totalEmeraldBlockCount;
    }

    public int getTotalEmeraldOreCount() {
        return totalEmeraldOreCount;
    }

    public int getTotalPumpkinCount() {
        return totalPumpkinCount;
    }

    public int getTotalWheatCount() {
        return totalWheatCount;
    }

    public int getTotalBreadCount() {
        return totalBreadCount;
    }

    public int getTotalCoalCount() {
        return totalCoalCount;
    }

    public int getTotalEmeraldGreenDyeCount() {
        return totalEmeraldGreenDyeCount;
    }

    /** Returns planks plus four times every stored log. */
    public int getTotalPlankCount() {
        return totalPlankCount;
    }

    /** Reads the current stock of one market item from the linked chests. */
    public int getMarketStock(ServerLevel level, Item item) {
        int total = 0;
        for (BlockPos chestPos : cachedChestPositions) {
            if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (isLogMarketItem(item) ? stack.is(ItemTags.LOGS)
                        : isCoalMarketItem(item) ? stack.is(ItemTags.COALS) : stack.is(item)) {
                    total = Math.addExact(total, stack.getCount());
                }
            }
        }
        return total;
    }

    /** The canonical log market item represents all wood-log variants. */
    public static boolean isLogMarketItem(Item item) {
        return item == Items.OAK_LOG;
    }

    /** The canonical coal market item represents both coal and charcoal. */
    public static boolean isCoalMarketItem(Item item) {
        return item == Items.COAL;
    }

    /** Reads the current emerald value without relying on the periodic cache refresh. */
    public int getLiveEmeraldValue(ServerLevel level) {
        int total = 0;
        for (BlockPos chestPos : cachedChestPositions) {
            if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.is(Items.EMERALD)) {
                    total = Math.addExact(total, stack.getCount());
                } else if (stack.is(Items.EMERALD_BLOCK)) {
                    total = Math.addExact(total, Math.multiplyExact(stack.getCount(), 9));
                }
            }
        }
        return total;
    }

    /** Returns the population used by population-scaled market demand. */
    public int getMarketPopulation(ServerLevel level) {
        if (villageId == null) {
            return 1;
        }
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return 1;
        }
        VillageRecord village = VillageRegistryData.get(overworld).getVillages().get(villageId);
        return village == null ? 1 : Math.max(1, village.getMembers().size());
    }

    public AABB getSearchBox() {
        return new AABB(worldPosition).inflate(SEARCH_RADIUS);
    }

    public String getBankName() {
        return bankName;
    }

    @Nullable
    public BlockPos getGolemConstructionPos() {
        return golemConstructionPos;
    }

    public void setGolemConstructionPos(@Nullable BlockPos pos) {
        golemConstructionPos = pos == null ? null : pos.immutable();
        setChanged();
    }

    /** Returns whether this bank can reserve its next Emerald Golem construction. */
    public boolean canBeginGolemConstruction() {
        return activeGolemConstructionVillager == null
                && golemConstructionPos != null
                && getRegisteredEmeraldGolemCount() < getExpectedEmeraldGolemCount()
                // Emerald blocks can be crafted from the bank's raw emerald reserve by
                // the Emeraldsmith, so this gate must validate the movable emerald
                // value rather than require four pre-crafted block items.
                && totalEmeraldCount >= 36
                && totalPumpkinCount >= 1;
    }

    /** Returns whether the bank can reserve its next Emerald Skrimisher construction. */
    public boolean canBeginSkrimisherConstruction(ServerLevel level) {
        return activeGolemConstructionVillager == null
                && golemConstructionPos != null
                && getRegisteredEmeraldGolemCount() >= getExpectedEmeraldGolemCount()
                && getEmeraldSkrimisherCount(level) < getEmeraldSkrimisherLimit(level)
                && (totalEmeraldBlockCount >= 1 || totalEmeraldCount >= 9)
                && totalPumpkinCount >= 1
                && hasStoredItem(level, ECAPItems.EMERALD_CHEST.get());
    }

    /** Acquires the single construction slot for a server-side Emeraldsmith. */
    public boolean beginSkrimisherConstruction(ServerLevel level, UUID villagerId) {
        if (!canBeginSkrimisherConstruction(level)) {
            return false;
        }
        activeGolemConstructionVillager = villagerId;
        return true;
    }

    /** Acquires the single construction slot for a server-side Emeraldsmith. */
    public boolean beginGolemConstruction(UUID villagerId) {
        if (!canBeginGolemConstruction()) {
            return false;
        }
        activeGolemConstructionVillager = villagerId;
        return true;
    }

    /** Releases the construction slot after completion or a failed attempt. */
    public void endGolemConstruction(UUID villagerId) {
        if (villagerId.equals(activeGolemConstructionVillager)) {
            activeGolemConstructionVillager = null;
        }
    }

    /** Receives an inventory mutation from a linked Emerald Chest. */
    void onLinkedChestChanged(BlockPos chestPos, EmeraldChestBlockEntity.StoredCounts counts) {
        BlockPos immutablePos = chestPos.immutable();
        if (!cachedChestPositions.contains(immutablePos)) {
            return;
        }
        cachedChestTotals.put(immutablePos, counts);
        if (level instanceof ServerLevel serverLevel) {
            refreshInventoryTotals(serverLevel);
        }
    }

    private void unlinkCachedChests(ServerLevel level) {
        for (BlockPos chestPos : cachedChestPositions) {
            if (level.isLoaded(chestPos)
                    && level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest) {
                chest.unlinkBank(worldPosition);
            }
        }
    }

    /** Exposes the transient reservation owner for server-side diagnostics. */
    @Nullable
    public UUID getActiveGolemConstructionVillager() {
        return activeGolemConstructionVillager;
    }

    public int getRegisteredEmeraldGolemCount() {
        return emeraldGolemEmployeeIds.size();
    }

    public void setBankName(String name) {
        String normalizedName = Objects.requireNonNull(name, "name").trim();
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Bank name cannot be blank");
        }
        this.bankName = ProtocolStringLimits.clamp(
                normalizedName, ProtocolStringLimits.MAX_BANK_NAME_LENGTH);
        setChanged();
    }

    // MenuProvider

    @Override
    public @NotNull Component getDisplayName() {
        return Component.literal(bankName.isEmpty() ? "Village Bank" : bankName);
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, @NotNull Inventory playerInventory, @NotNull Player player) {
        return new BankMenu(containerId, playerInventory, this, player.getUUID());
    }

    /** Writes the buffer required by the client-side menu constructor. */
    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        UUID viewerId = menu instanceof BankMenu bankMenu ? bankMenu.getViewerId() : null;
        writeMenuOpenData(buf, viewerId);
    }

    public void writeMenuOpenData(FriendlyByteBuf buf) {
        writeMenuOpenData(buf, null);
    }

    public void writeMenuOpenData(FriendlyByteBuf buf, @Nullable UUID viewerId) {
        BankMenuOpenData.write(buf, BankMenuOpenDataFactory.create(this, viewerId));
    }

    public List<BankMenu.MarketEntry> buildMarketEntries() {
        return BankMenuOpenDataFactory.buildMarketEntries(this);
    }

    /** Returns the demand target in the same unit as the configured market item. */
    public int getMarketTarget(ServerLevel level, MarketItem marketItem) {
        return switch (marketItem.config().demandSource()) {
            case BANK_PUMPKIN_TARGET -> getPumpkinTarget();
            case BANK_COAL_TARGET -> getCoalTarget();
            case BANK_TARGET, BANK_PLANK_TARGET -> isLogMarketItem(marketItem.item())
                    ? Math.max(1, (getPlankTarget() + 3) / 4)
                    : getPlankTarget();
            case POPULATION -> 1;
        };
    }

    // NBT serialization

    /** Resets all values that are derived from the live server world or active work. */
    private void resetDerivedAndTransientStateAfterLoad() {
        cachedChestPositions.clear();
        cachedChestTotals.clear();
        closestEmeraldProcessorPos = null;
        totalEmeraldCount = 0;
        totalEmeraldBlockCount = 0;
        totalEmeraldOreCount = 0;
        totalPumpkinCount = 0;
        totalWheatCount = 0;
        totalBreadCount = 0;
        totalCoalCount = 0;
        totalEmeraldGreenDyeCount = 0;
        totalPlankCount = 0;
        nextFullScanTick = Long.MIN_VALUE;
        nextVerifyTick = Long.MIN_VALUE;
        chestCacheDirty = true;
        processorCacheDirty = true;

        depositQueue.clear();
        queuedDepositors.clear();
        currentDepositor = null;
        activeGoal = null;
        pendingGoalRemovals.clear();
        transferChestCloseTicks.clear();
        activeGolemConstructionVillager = null;
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        PersistedState.CODEC.encodeStart(NbtOps.INSTANCE, PersistedState.from(this))
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "Could not encode bank durable state: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .ifPresent(tag::merge);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        resetDerivedAndTransientStateAfterLoad();
        PersistedState.CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                        "Ignoring malformed bank durable state: {}", message))
                .orElseGet(PersistedState::empty)
                .applyTo(this);
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            Set<BankBlockEntity> banks = LOADED_BANKS.get(serverLevel);
            if (banks != null) {
                banks.remove(this);
                if (banks.isEmpty()) {
                    LOADED_BANKS.remove(serverLevel);
                }
            }
            unlinkCachedChests(serverLevel);
        }
        super.setRemoved();
    }
}
