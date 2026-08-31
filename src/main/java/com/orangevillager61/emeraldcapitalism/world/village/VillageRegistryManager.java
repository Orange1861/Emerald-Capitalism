package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.event.VillagerSpawnEvents;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIAccessPolicy;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIDataCache;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIDataFactory;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIDataPacket;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.village.pipeline.VillageGenerationPipeline;
import com.orangevillager61.emeraldcapitalism.world.village.scan.InitialVillageScanChunkLoadPool;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.util.SharedScanGenerationBudget;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Per-level service that periodically scans villages, updating the {@link VillageRegistryData}
 * with current villager POI information read from villager Brain memories.
 */
public class VillageRegistryManager {

    /** Limits the entity-query portion of a scan to a small number of chunks per tick. */
    private static final int ENTITY_QUERY_CHUNKS_PER_TICK = 2;

    private final ServerLevel level;
    private final InitialVillageScanChunkLoadPool initialScanChunkLoadPool;
    private VillageRegistryData registryData;

    /** Round-robin queue of village UUIDs to scan. */
    private final ArrayDeque<UUID> scanQueue = new ArrayDeque<>();
    /** Full block-cache scans queued by POI requests and manual refreshes. */
    private final ArrayDeque<UUID> fullScanQueue = new ArrayDeque<>();
    /** Budgeted villager refreshes that must run before a completed full scan is published. */
    private final ArrayDeque<UUID> fullScanMemberRefreshQueue = new ArrayDeque<>();
    /** Full scans whose completion snapshot is waiting for its villager refresh. */
    private final Set<UUID> fullScanCompletionPending = new HashSet<>();
    /** Players awaiting an automatic fresh POI snapshot when their requested scan completes. */
    private final Map<UUID, Set<UUID>> fullScanListeners = new HashMap<>();
    /** Uninitialized-cache scans waiting for bank and farm generation to finish. */
    private final Map<UUID, Boolean> deferredInitialFullScans = new HashMap<>();
    @Nullable
    private UUID currentFullScanVillageId;

    /** Tick counter for scheduling scan cycles. */
    private long tickCounter;

    // Per-scan state (processing a single village across ticks)

    /** The village currently being scanned, or null if idle. */
    @Nullable
    private UUID currentVillageId;

    /** Villagers found inside the bounding box, pending processing. */
    private final ArrayDeque<Villager> pendingEntities = new ArrayDeque<>();

    /** UUIDs of villagers we found in-world during this scan (to detect departures). */
    private final Set<UUID> foundInWorld = new HashSet<>();

    /** Prevents villagers on chunk-query boundaries from being queued twice. */
    private final Set<UUID> queuedEntityIds = new HashSet<>();

    /** Whether all chunk-sized entity queries have completed for the current scan. */
    private boolean entityQueryDone;

    private boolean entityScanInitialized;
    private int scanMinChunkX;
    private int scanMaxChunkX;
    private int scanMinChunkZ;
    private int scanMaxChunkZ;
    private int nextScanChunkX;
    private int nextScanChunkZ;
    @Nullable
    private AABB entityScanBox;

    /** Whether departure processing has been performed for the current village. */
    private boolean departuresDone;

    public VillageRegistryManager(ServerLevel level) {
        this.level = level;
        this.initialScanChunkLoadPool = new InitialVillageScanChunkLoadPool(level);
    }

    // Main tick entry point

    /**
     * Called once per server tick for this level.
     */
    public void tick(ServerLevel level) {
        if (registryData == null) {
            registryData = VillageRegistryData.get(level);
        }

        tickCounter++;
        promoteDeferredInitialFullScans();

        if (tickCounter % 20 == 0) {
            refreshVillageGovernance();
        }

        if (currentFullScanVillageId != null || !fullScanQueue.isEmpty()) {
            processFullScanBudget();
        }

        // Continue any in-progress village scan.
        if (currentVillageId != null) {
            processScanBudget();
            return;
        }

        // A full scan is not complete from the player's perspective until its
        // villager data has been refreshed and can be included in the snapshot.
        if (!fullScanMemberRefreshQueue.isEmpty()) {
            startNextFullScanMemberRefresh();
            return;
        }

        // If there are villages still queued from the previous cycle, advance one step
        if (!scanQueue.isEmpty()) {
            startNextVillage();
            return;
        }

        // Check if a new scan cycle is due
        if (tickCounter % Config.villageScanIntervalTicks != 0) {
            return;
        }

        // Rebuild the scan queue from the current set of villages and start the first
        rebuildScanQueue();
        startNextVillage();
    }

    /** Rechecks the one candidate against live opinion, Mayor presence, and bank control. */
    private void refreshVillageGovernance() {
        boolean periodicMayorAudit = tickCounter % 200 == 0;
        for (VillageRecord village : registryData.getVillages().values()) {
            boolean changed = VillageGovernance.refresh(level, village);
            // A normal village already has a recorded Mayor and receives
            // immediate succession checks from villager-death events. Keep a
            // slower reconciliation pass for chunk unloads or missed events,
            // but do not run a bounding-box entity query for every village
            // every second.
            boolean noRecordedMayor = village.getMembers().values().stream()
                    .noneMatch(member -> "MAYOR".equals(member.getProfession()));
            if (periodicMayorAudit || noRecordedMayor) {
                changed |= VillageGovernance.refreshMayorIfVacant(level, village);
            }
            if (changed) {
                registryData.setDirty();
                VillagePOIDataCache.invalidateVillage(village.getVillageId());
            }
        }
    }

    // Scan queue management

    private void rebuildScanQueue() {
        scanQueue.clear();
        scanQueue.addAll(registryData.getVillages().keySet());
    }

    private void startNextVillage() {
        UUID villageId = scanQueue.poll();
        if (villageId == null) {
            return;
        }
        startVillageScan(villageId);
    }

    private void startNextFullScanMemberRefresh() {
        UUID villageId = fullScanMemberRefreshQueue.poll();
        if (villageId != null) {
            startVillageScan(villageId);
        }
    }

    private void startVillageScan(UUID villageId) {
        currentVillageId = villageId;
        pendingEntities.clear();
        foundInWorld.clear();
        queuedEntityIds.clear();
        entityQueryDone = false;
        entityScanInitialized = false;
        entityScanBox = null;
        departuresDone = false;
        // Processing begins on the next call to processScanBudget() from tick()
    }

    // Per-tick budget-limited processing

    private void processScanBudget() {
        VillageRecord village = registryData.getVillages().get(currentVillageId);
        if (village == null) {
            // Village was removed while queued.
            finishCurrentVillage();
            return;
        }

        int budget = Config.villageScanVillagerBudget;

        // Step 1: Query entities in small chunks so a large village cannot create
        // one unbudgeted entity-query spike.
        if (!entityQueryDone) {
            if (!entityScanInitialized) {
                initializeEntityScan(village.getBoundingBox());
            }
            queryEntityChunks();
        }

        // Step 2: Process pending villagers up to budget
        int processed = 0;
        while (!pendingEntities.isEmpty() && processed < budget) {
            Villager villager = pendingEntities.poll();
            processVillager(village, villager);
            processed++;
        }

        // If still have pending entities, wait for next tick
        if (!pendingEntities.isEmpty()) {
            return;
        }

        // More chunks may still need to be queried even when the chunks processed
        // this tick contained no villagers.
        if (!entityQueryDone) {
            return;
        }

        // Step 3: Handle departures (once, after all entities processed)
        if (!departuresDone) {
            processDepartures(village);
            departuresDone = true;
        }

        finishCurrentVillage();
    }

    private void finishCurrentVillage() {
        UUID finishedVillageId = currentVillageId;
        currentVillageId = null;
        pendingEntities.clear();
        foundInWorld.clear();
        queuedEntityIds.clear();
        entityScanBox = null;

        if (finishedVillageId != null && fullScanCompletionPending.remove(finishedVillageId)) {
            VillageRecord village = registryData.getVillages().get(finishedVillageId);
            if (village == null) {
                notifyFullScanUnavailable(finishedVillageId);
                return;
            }
            village.completeFullScan();
            VillagePOIDataCache.invalidateVillage(finishedVillageId);
            notifyFullScanListeners(village);
        }
    }

    private void abandonCurrentVillageScan() {
        currentVillageId = null;
        pendingEntities.clear();
        foundInWorld.clear();
        queuedEntityIds.clear();
        entityScanBox = null;
    }

    private void initializeEntityScan(AABB box) {
        entityScanBox = box;
        scanMinChunkX = ((int) Math.floor(box.minX)) >> 4;
        scanMaxChunkX = ((int) Math.floor(box.maxX)) >> 4;
        scanMinChunkZ = ((int) Math.floor(box.minZ)) >> 4;
        scanMaxChunkZ = ((int) Math.floor(box.maxZ)) >> 4;
        nextScanChunkX = scanMinChunkX;
        nextScanChunkZ = scanMinChunkZ;
        entityScanInitialized = true;
    }

    private void queryEntityChunks() {
        if (entityScanBox == null) {
            entityQueryDone = true;
            return;
        }

        int remaining = ENTITY_QUERY_CHUNKS_PER_TICK;
        while (!entityQueryDone && remaining-- > 0) {
            if (level.hasChunk(nextScanChunkX, nextScanChunkZ)) {
                double minX = nextScanChunkX * 16.0D;
                double minZ = nextScanChunkZ * 16.0D;
                AABB chunkBox = new AABB(minX, entityScanBox.minY, minZ,
                        minX + 16.0D, entityScanBox.maxY, minZ + 16.0D);
                for (Villager villager : level.getEntitiesOfClass(Villager.class, chunkBox)) {
                    if (entityScanBox.intersects(villager.getBoundingBox())
                            && queuedEntityIds.add(villager.getUUID())) {
                        pendingEntities.add(villager);
                    }
                }
            }
            advanceEntityScanCursor();
        }
    }

    private void advanceEntityScanCursor() {
        if (nextScanChunkX < scanMaxChunkX) {
            nextScanChunkX++;
        } else if (nextScanChunkZ < scanMaxChunkZ) {
            nextScanChunkX = scanMinChunkX;
            nextScanChunkZ++;
        } else {
            entityQueryDone = true;
        }
    }

    private void processFullScanBudget() {
        if (currentFullScanVillageId == null) {
            currentFullScanVillageId = fullScanQueue.poll();
            if (currentFullScanVillageId == null) {
                return;
            }
        }

        VillageRecord village = registryData.getVillages().get(currentFullScanVillageId);
        if (village == null) {
            notifyFullScanUnavailable(currentFullScanVillageId);
            initialScanChunkLoadPool.finishScan(currentFullScanVillageId);
            currentFullScanVillageId = null;
            return;
        }

        UUID villageId = currentFullScanVillageId;
        if (!SharedScanGenerationBudget.tryAcquire(level.getServer(),
                SharedScanGenerationBudget.WorkType.SCAN)) {
            return;
        }
        boolean complete = PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.VILLAGE_FULL_SCAN,
                () -> village.processFullScan(level, Config.villageFullScanBlockBudget,
                        initialScanChunkLoadPool));
        if (complete) {
            initialScanChunkLoadPool.finishScan(villageId);
            registryData.setDirty();
            currentFullScanVillageId = null;
            queueFullScanMemberRefresh(villageId);
        } else {
            // Requeue incomplete scans so a large village cannot monopolize the full-scan queue.
            fullScanQueue.add(villageId);
            currentFullScanVillageId = null;
        }
    }

    /** Starts generation-gated scans only after all bank and farm pipeline work has finished. */
    private void promoteDeferredInitialFullScans() {
        Iterator<Map.Entry<UUID, Boolean>> iterator = deferredInitialFullScans.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Boolean> entry = iterator.next();
            UUID villageId = entry.getKey();
            if (VillageGenerationPipeline.isQueuedOrActive(level, villageId)) {
                continue;
            }
            boolean adaptive = entry.getValue();
            iterator.remove();

            VillageRecord village = registryData.getVillages().get(villageId);
            if (village == null) {
                notifyFullScanUnavailable(villageId);
                continue;
            }
            if (villageId.equals(currentFullScanVillageId) || fullScanQueue.contains(villageId)
                    || fullScanCompletionPending.contains(villageId)) {
                continue;
            }
            queueFullScan(village, adaptive && !village.isCacheInitialized());
        }
    }

    private void queueFullScanMemberRefresh(UUID villageId) {
        if (villageId.equals(currentVillageId)) {
            // Re-query after the full scan so membership uses the new bounds and fresh cache.
            abandonCurrentVillageScan();
        }
        scanQueue.removeIf(villageId::equals);
        if (fullScanCompletionPending.add(villageId)) {
            fullScanMemberRefreshQueue.add(villageId);
        }
    }

    private void notifyFullScanListeners(VillageRecord village) {
        Set<UUID> listenerIds = fullScanListeners.remove(village.getVillageId());
        if (listenerIds == null) {
            return;
        }
        for (UUID playerId : listenerIds) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            if (player.serverLevel() == level
                    && VillagePOIAccessPolicy.isLocalContextValid(player, level, village)) {
                boolean isOp = player.hasPermissions(Config.villageCommandPermissionLevel);
                PacketDistributor.sendToPlayer(player, VillagePOIDataFactory.build(village, level, isOp, player));
            } else {
                // End the client-side loading state without exposing village data outside
                // the requester's current local context.
                PacketDistributor.sendToPlayer(player, VillagePOIDataPacket.empty());
            }
        }
    }

    /** Clears loading state when a queued village no longer exists. */
    private void notifyFullScanUnavailable(UUID villageId) {
        Set<UUID> listenerIds = fullScanListeners.remove(villageId);
        if (listenerIds == null) {
            return;
        }
        for (UUID playerId : listenerIds) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, VillagePOIDataPacket.empty());
            }
        }
    }

    // Full-scan request management

    /**
     * Queues a full village cache refresh. The world scan is advanced over later ticks
     * so a player request cannot monopolize the server thread.
     *
     * @return true when a new scan was queued, false when one is already pending
     */
    public boolean requestFullScan(VillageRecord village) {
        return requestFullScan(village, null);
    }

    /** Queues a full scan and optionally subscribes the requester to its completion snapshot. */
    public boolean requestFullScan(VillageRecord village, @Nullable ServerPlayer requester) {
        UUID villageId = village.getVillageId();
        if (requester != null) {
            fullScanListeners.computeIfAbsent(villageId, ignored -> new HashSet<>()).add(requester.getUUID());
        }
        if (villageId.equals(currentFullScanVillageId) || fullScanQueue.contains(villageId)
                || fullScanCompletionPending.contains(villageId)
                || deferredInitialFullScans.containsKey(villageId)) {
            return false;
        }

        if (!village.isCacheInitialized()
                && VillageGenerationPipeline.isQueuedOrActive(level, villageId)) {
            deferredInitialFullScans.put(villageId, true);
            VillagePOIDataCache.invalidateVillage(villageId);
            return true;
        }

        queueFullScan(village, !village.isCacheInitialized());
        return true;
    }

    private void queueFullScan(VillageRecord village, boolean adaptive) {
        UUID villageId = village.getVillageId();
        initialScanChunkLoadPool.finishScan(villageId);
        village.beginFullScan(adaptive);
        VillagePOIDataCache.invalidateVillage(villageId);
        fullScanQueue.add(villageId);
    }

    /** Cancels an initial scan that was waiting on a generation pipeline which failed. */
    public void cancelDeferredInitialFullScan(UUID villageId) {
        if (deferredInitialFullScans.remove(villageId) != null) {
            notifyFullScanUnavailable(villageId);
        }
    }

    /**
     * Replaces a pending or active full scan after its bounds have changed.
     * The replacement scan captures the village's current bounds before its next slice.
     */
    public void restartFullScan(VillageRecord village, @Nullable ServerPlayer requester) {
        UUID villageId = village.getVillageId();
        if (requester != null) {
            fullScanListeners.computeIfAbsent(villageId, ignored -> new HashSet<>()).add(requester.getUUID());
        }

        fullScanCompletionPending.remove(villageId);
        fullScanMemberRefreshQueue.removeIf(villageId::equals);
        if (villageId.equals(currentVillageId)) {
            abandonCurrentVillageScan();
        }

        if (!village.isCacheInitialized()
                && VillageGenerationPipeline.isQueuedOrActive(level, villageId)) {
            initialScanChunkLoadPool.finishScan(villageId);
            if (villageId.equals(currentFullScanVillageId)) {
                currentFullScanVillageId = null;
            }
            fullScanQueue.removeIf(villageId::equals);
            // An explicit expanded-bounds refresh remains exhaustive when promoted.
            deferredInitialFullScans.put(villageId, false);
            VillagePOIDataCache.invalidateVillage(villageId);
            return;
        }

        deferredInitialFullScans.remove(villageId);
        initialScanChunkLoadPool.finishScan(villageId);
        // Expanded/manual replacement scans must inspect the complete requested bounds.
        village.beginFullScan(false);
        VillagePOIDataCache.invalidateVillage(villageId);
        if (!villageId.equals(currentFullScanVillageId) && !fullScanQueue.contains(villageId)) {
            fullScanQueue.add(villageId);
        }
    }

    // Event-driven updates

    /**
     * Called when a villager dies. Immediately removes them from any village
     * they belong to, bypassing the departure grace period.
     */
    public void handleVillagerDeath(Villager villager) {
        handleVillagerDeath(villager, null);
    }

    /** Removes a dead villager and records a direct killer for the last Bank employee. */
    public void handleVillagerDeath(Villager villager, @Nullable Player directKiller) {
        if (registryData == null) {
            registryData = VillageRegistryData.get(level);
        }

        UUID villagerUUID = villager.getUUID();
        for (VillageRecord village : registryData.getVillages().values()) {
            if (village.hasMember(villagerUUID)) {
                village.removeMember(villagerUUID);
                BlockPos bankPos = registryData.getBankPos(village.getVillageId());
                if (bankPos != null && level.getBlockEntity(bankPos) instanceof BankBlockEntity bank) {
                    if (directKiller != null) {
                        bank.recordLastEmployeeDirectKiller(
                                villagerUUID, directKiller.getUUID(), level.getGameTime());
                    }
                    bank.removeEmployee(villagerUUID);
                }
                if (villager.getVillagerData().getProfession()
                        == com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions.MAYOR.get()) {
                    VillageGovernance.refresh(level, village);
                    VillageGovernance.refreshMayorIfVacant(level, village);
                }
                registryData.setDirty();
                EmeraldCapitalism.LOGGER.debug("Removed dead villager {} from village {}",
                        villager.getName().getString(), village.getVillageId());
                return;
            }
        }
    }

    /**
     * Called when a villager spawns or loads into the world. If they are inside
     * a village bounding box and not yet registered, adds them immediately.
     */
    public void handleVillagerSpawnOrLoad(Villager villager) {
        if (registryData == null) {
            registryData = VillageRegistryData.get(level);
        }

        BlockPos pos = villager.blockPosition();
        VillageRecord village = registryData.getVillageFor(pos);
        if (village == null) {
            return; // Not inside any tracked village
        }

        if (!village.hasMember(villager.getUUID())) {
            processVillager(village, villager);
        } else {
            // Existing records still need an immediate employee/job-site check on
            // chunk load; the periodic village scan will refresh the rest of the POI data.
            updateBankEmployeeTracking(village, villager,
                    getMemoryBlockPos(villager, MemoryModuleType.JOB_SITE), false);
        }
    }

    // Villager processing

    private void processVillager(VillageRecord village, Villager villager) {
        UUID villagerUUID = villager.getUUID();
        foundInWorld.add(villagerUUID);

        // Naming is resolved only once the authoritative village record is
        // known. This is also the point at which the village allocator can
        // enforce no repeated element pairs before the pool is exhausted.
        VillagerNameManager.assignNameIfNeeded(villager, village);

        long gameTick = level.getGameTime();

        // Read POI data from the villager's Brain memories
        BlockPos bedPos = getMemoryBlockPos(villager, MemoryModuleType.HOME);
        BlockPos jobSitePos = getMemoryBlockPos(villager, MemoryModuleType.JOB_SITE);
        String profession = villager.getVillagerData().getProfession().name();
        String displayName = villager.getName().getString();
        float health = villager.getHealth();

        VillagerPOIRecord existing = village.getMembers().get(villagerUUID);

        if (existing != null) {
            // Update existing record if anything changed
            boolean changed = false;

            if (!Objects.equals(existing.getBedPos(), bedPos)) {
                existing.setBedPos(bedPos);
                changed = true;
            }
            if (!Objects.equals(existing.getJobSitePos(), jobSitePos)) {
                existing.setJobSitePos(jobSitePos);
                changed = true;
            }
            if (!existing.getProfession().equals(profession)) {
                existing.setProfession(profession);
                changed = true;
            }
            if (!existing.getDisplayName().equals(displayName)) {
                existing.setDisplayName(displayName);
                changed = true;
            }
            if (existing.getStatus() != VillagerPOIRecord.Status.ACTIVE) {
                existing.setStatus(VillagerPOIRecord.Status.ACTIVE);
                existing.setDepartureCounter(0);
                changed = true;
            }
            if (Float.compare(existing.getHealth(), health) != 0) {
                existing.setHealth(health);
                changed = true;
            }

            if (existing.getLastVerifiedTick() != gameTick) {
                existing.setLastVerifiedTick(gameTick);
                changed = true;
            }

            if (changed) {
                registryData.setDirty();
            }
        } else {
            // New villager: create a fresh record
            VillagerPOIRecord record = new VillagerPOIRecord(
                    villagerUUID,
                    displayName,
                    profession,
                    bedPos,
                    jobSitePos,
                    null, // familyId: populated by other systems
                    health,
                    VillagerPOIRecord.Status.ACTIVE,
                    0,
                    gameTick
            );
            village.addMember(record);
            registryData.setDirty();
            EmeraldCapitalism.LOGGER.debug("Registered new villager {} in village {}",
                    displayName, village.getVillageId());

            // Open a bank account for the villager if this village has a registered bank.
            openBankAccountIfApplicable(villagerUUID, village.getVillageId());
        }

        updateBankEmployeeTracking(village, villager, jobSitePos, existing == null);
    }

    /** Releases transient chunk tickets before this level's manager is discarded. */
    public void shutdown() {
        deferredInitialFullScans.clear();
        initialScanChunkLoadPool.close();
    }

    /**
     * Keeps Bank employee state tied to the server-authoritative villager POI
     * memory rather than to client-side profession visuals.
     */
    private void updateBankEmployeeTracking(VillageRecord village, Villager villager,
                                            @Nullable BlockPos jobSitePos, boolean newlyRegistered) {
        BlockPos bankPos = registryData.getBankPos(village.getVillageId());
        if (bankPos == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(bankPos);
        if (!(blockEntity instanceof BankBlockEntity bank)) {
            return;
        }

        if (newlyRegistered) {
            bank.registerSpawnedEmployee(villager);
        }
        bank.registerEmployeeFromJob(level, villager, jobSitePos);

        int initialEmeralds = VillagerSpawnEvents.getPendingInitialEmeralds(villager);
        if (initialEmeralds > 0) {
            bank.depositInitialEmeralds(level, villager, initialEmeralds);
            VillagerSpawnEvents.clearPendingInitialEmeralds(villager);
        }
        bank.queueDepositIfEligible(villager);
    }

    /**
     * Opens a {@link BankAccountData} account for the newly-registered villager if, and only if,
     * the village's current village manager has a bank registered.
     *
     * @param villagerUUID the UUID of the villager who was just added
     * @param villageId    the village they were registered into
     */
    private void openBankAccountIfApplicable(UUID villagerUUID, UUID villageId) {
        BlockPos vmPos = registryData.getVMPos(villageId);
        if (vmPos == null) return;

        BlockEntity be = level.getBlockEntity(vmPos);
        if (!(be instanceof VillageManagerBlockEntity vm)) return;

        if (vm.hasBankRegistered()) {
            BankAccountData.get(level).openAccount(villagerUUID);
        }
    }

    /**
     * Reads a {@link GlobalPos} memory from a villager's Brain and returns just the BlockPos,
     * or null if the memory is absent or for a different dimension.
     */
    @Nullable
    private BlockPos getMemoryBlockPos(Villager villager, MemoryModuleType<GlobalPos> memoryType) {
        return villager.getBrain().getMemory(memoryType)
                .filter(gp -> gp.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .orElse(null);
    }

    // Departure handling

    private void processDepartures(VillageRecord village) {
        int threshold = Config.villageScanDepartureThreshold;
        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, VillagerPOIRecord> entry : village.getMembers().entrySet()) {
            UUID villagerUUID = entry.getKey();
            VillagerPOIRecord record = entry.getValue();

            if (foundInWorld.contains(villagerUUID)) {
                continue; // Present: already handled
            }

            // Not found in entity query: increment departure counter
            int newCount = record.getDepartureCounter() + 1;
            record.setDepartureCounter(newCount);
            record.setStatus(VillagerPOIRecord.Status.DEPARTED);
            registryData.setDirty();

            if (newCount >= threshold) {
                toRemove.add(villagerUUID);
                EmeraldCapitalism.LOGGER.debug("Removing villager {} from village {} (absent for {} scans)",
                        record.getDisplayName(), village.getVillageId(), newCount);
            }
        }

        for (UUID uuid : toRemove) {
            village.removeMember(uuid);
        }

        if (!toRemove.isEmpty()) {
            registryData.setDirty();
        }
    }
}
