package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageLumbermillStructurePlacer;
import com.orangevillager61.emeraldcapitalism.world.village.naming.worldgen.WorldgenVillageNameAssigner;
import com.orangevillager61.emeraldcapitalism.world.village.pipeline.VillageGenerationContext;
import com.orangevillager61.emeraldcapitalism.world.village.pipeline.VillageGenerationPipeline;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Automatically detects village structures on chunk load and registers them
 * in {@link VillageRegistryData} without requiring manual commands.
 * Also places a Village Manager block near the bell and processes
 * pending placements for villages whose bell chunk wasn't loaded yet.
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public class VillageDetectionHandler {

    private static final ConcurrentLinkedQueue<PendingVillageRegistration> PENDING = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ActiveTask> ACTIVE_SEARCHES = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingNameAssignment> PENDING_NAME_ASSIGNMENTS = new ConcurrentLinkedQueue<>();
    private static final Set<VillageOriginKey> QUEUED_OR_ACTIVE_ORIGINS = ConcurrentHashMap.newKeySet();
    /** Village IDs for which a RetryPlacementTask is already in ACTIVE_SEARCHES. */
    private static final Set<UUID> QUEUED_RETRY_IDS = ConcurrentHashMap.newKeySet();
    /** Structure details retained while a persisted pending-bell record is being retried. */
    private static final Map<UUID, DetectedVillageContext> DETECTED_CONTEXTS = new ConcurrentHashMap<>();
    private static final Map<PoolLumbermillKey, PendingPoolLumbermill> PENDING_POOL_LUMBERMILLS =
            new ConcurrentHashMap<>();
    private static final VillageLumbermillStructurePlacer LUMBERMILL_PLACER =
            new VillageLumbermillStructurePlacer();

    /** Cap new registrations promoted from PENDING each server tick. */
    private static final int MAX_NEW_REGISTRATIONS_PER_TICK = 2;
    /** Cap how many active bell-search tasks we advance each server tick. */
    private static final int MAX_ACTIVE_TASKS_PER_TICK = 4;
    /** Per-task scan budget for bell search per tick. */
    private static final int BELL_SCAN_BLOCK_BUDGET_PER_TASK_PER_TICK = 4096;

    private static final WorldgenVillageNameAssigner WORLDGEN_NAME_ASSIGNER = new WorldgenVillageNameAssigner();

    /** Only retry pending placements every 100 ticks (5 seconds). */
    private static final int PENDING_RETRY_INTERVAL = 100;
    /** Delay worldgen root-name assignment slightly so village cache/full-scan can settle first. */
    private static final int NAME_ASSIGNMENT_DELAY_TICKS = 40;
    private static long tickCounter;

    /** Clears transient tasks that retain world references between server lifecycles. */
    public static void clearPendingWork() {
        PENDING.clear();
        ACTIVE_SEARCHES.clear();
        PENDING_NAME_ASSIGNMENTS.clear();
        QUEUED_OR_ACTIVE_ORIGINS.clear();
        QUEUED_RETRY_IDS.clear();
        DETECTED_CONTEXTS.clear();
        PENDING_POOL_LUMBERMILLS.clear();
        VillageGenerationPipeline.clearPendingWork();
        tickCounter = 0;
    }

    /** Common interface for budgeted bell-search tasks advanced each server tick. */
    private interface ActiveTask {
        /** @return true when the task is finished (found or exhausted), false to continue next tick */
        boolean processStep();
    }

    private record PendingVillageRegistration(ServerLevel level, ChunkPos originChunk,
                                              BoundingBox boundingBox, List<StructurePiece> pieces) {}

    private record VillageOriginKey(ServerLevel level, int chunkX, int chunkZ) {
        private static VillageOriginKey of(ServerLevel level, ChunkPos pos) {
            return new VillageOriginKey(level, pos.x, pos.z);
        }
    }

    private record PendingNameAssignment(ServerLevel level, UUID villageId, long executeAtTick,
                                         @Nullable String assignedName) {}

    private record DetectedVillageContext(BlockPos structureCenter, BoundingBox structureBox,
                                          List<StructurePiece> pieces) {}

    private record PoolLumbermillKey(ServerLevel level, long structureKey) {}

    private record PendingPoolLumbermill(ServerLevel level, long structureKey,
                                         BoundingBox placementBox) {}

    private static final class PendingVillageRegistrationTask implements ActiveTask {
        private final ServerLevel level;
        private final ChunkPos originChunk;
        private final BoundingBox structureBox;
        private final List<StructurePiece> pieces;
        private final UUID villageId = UUID.randomUUID();
        private final BellSearchCursor searchCursor;

        private PendingVillageRegistrationTask(ServerLevel level, ChunkPos originChunk,
                                               BoundingBox structureBox, List<StructurePiece> pieces) {
            this.level = level;
            this.originChunk = originChunk;
            this.structureBox = structureBox;
            this.pieces = List.copyOf(pieces);

            List<BoundingBox> searchOrder = new ArrayList<>();
            if (!this.pieces.isEmpty()) {
                searchOrder.add(this.pieces.getFirst().getBoundingBox());
                for (int i = 1; i < this.pieces.size(); i++) {
                    searchOrder.add(this.pieces.get(i).getBoundingBox());
                }
            }
            searchOrder.add(structureBox);
            searchOrder.add(structureBox.inflatedBy(16));
            this.searchCursor = new BellSearchCursor(searchOrder);
        }

        @Override
        public boolean processStep() {
            BellSearchCursor.StepResult result = searchCursor.scan(level, BELL_SCAN_BLOCK_BUDGET_PER_TASK_PER_TICK);
            if (result == BellSearchCursor.StepResult.CONTINUE) {
                return false;
            }

            try {
                finishRegistration(level, villageId, originChunk, structureBox,
                        searchCursor.getFoundBell(), pieces);
            } finally {
                QUEUED_OR_ACTIVE_ORIGINS.remove(VillageOriginKey.of(level, originChunk));
            }
            return true;
        }
    }

    private static final class BellSearchCursor {
        private enum StepResult { CONTINUE, FOUND, NOT_FOUND }

        private final List<BoundingBox> boxes;
        private int boxIndex;

        private int x;
        private int y;
        private int z;
        private boolean boxInitialized;

        private BlockPos boxCenter;
        private BlockPos closestInBox;
        private double closestDistSq;
        @Nullable
        private BlockPos foundBell;

        private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        private BellSearchCursor(List<BoundingBox> boxes) {
            this.boxes = boxes;
        }

        private @Nullable BlockPos getFoundBell() {
            return foundBell;
        }

        private StepResult scan(ServerLevel level, int budget) {
            while (budget > 0 && boxIndex < boxes.size()) {
                BoundingBox box = boxes.get(boxIndex);
                if (!boxInitialized) {
                    x = box.minX();
                    y = box.minY();
                    z = box.minZ();
                    boxInitialized = true;
                    boxCenter = new BlockPos(box.getCenter().getX(), box.getCenter().getY(), box.getCenter().getZ());
                    closestInBox = null;
                    closestDistSq = Double.MAX_VALUE;
                }

                while (budget > 0) {
                    mutablePos.set(x, y, z);
                    if (level.hasChunk(mutablePos.getX() >> 4, mutablePos.getZ() >> 4)
                            && level.getBlockState(mutablePos).is(net.minecraft.world.level.block.Blocks.BELL)) {
                        double distSq = boxCenter.distSqr(mutablePos);
                        if (distSq < closestDistSq) {
                            closestDistSq = distSq;
                            closestInBox = mutablePos.immutable();
                        }
                    }
                    budget--;

                    if (++x > box.maxX()) {
                        x = box.minX();
                        if (++z > box.maxZ()) {
                            z = box.minZ();
                            if (++y > box.maxY()) {
                                if (closestInBox != null) {
                                    foundBell = closestInBox;
                                    return StepResult.FOUND;
                                }
                                boxIndex++;
                                boxInitialized = false;
                                break;
                            }
                        }
                    }
                }
            }

            return boxIndex >= boxes.size() ? StepResult.NOT_FOUND : StepResult.CONTINUE;
        }
    }

    /**
     * Budgeted retry task for villages whose bell chunk was not loaded during initial detection.
     * Scans the structure bounding box (and an inflated copy) over multiple ticks using
     * {@link BellSearchCursor}, then calls {@link #finishRetryRegistration} if the bell is found.
     */
    private static final class RetryPlacementTask implements ActiveTask {
        private final ServerLevel level;
        private final VillageRegistryData.PendingManagerPlacement pending;
        private final BellSearchCursor searchCursor;

        private RetryPlacementTask(ServerLevel level, VillageRegistryData.PendingManagerPlacement pending) {
            this.level = level;
            this.pending = pending;
            BoundingBox box = pending.structureBox();
            this.searchCursor = new BellSearchCursor(List.of(box, box.inflatedBy(16)));
        }

        @Override
        public boolean processStep() {
            BellSearchCursor.StepResult result = searchCursor.scan(level, BELL_SCAN_BLOCK_BUDGET_PER_TASK_PER_TICK);
            if (result == BellSearchCursor.StepResult.CONTINUE) {
                return false;
            }

            BlockPos bellPos = searchCursor.getFoundBell();
            if (bellPos != null) {
                finishRetryRegistration(level, pending, bellPos);
            }
            // Release the ID so the next retry cycle can re-queue if the bell still wasn't found.
            QUEUED_RETRY_IDS.remove(pending.villageId());
            return true;
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        ChunkAccess chunk = event.getChunk();
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) {
            return;
        }

        Registry<Structure> structureRegistry =
                com.orangevillager61.emeraldcapitalism.util.RegistryAccessCompat.get(
                        serverLevel.registryAccess(), Registries.STRUCTURE);

        VillageRegistryData data = VillageRegistryData.get(serverLevel);

        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (!start.isValid()) {
                continue;
            }

            ResourceLocation structureId = structureRegistry.getKey(entry.getKey());
            if (structureId == null || !isVillageStructure(structureId)) {
                continue;
            }

            for (StructurePiece piece : start.getPieces()) {
                queuePoolLumbermillPopulation(serverLevel, piece);
            }

            ChunkPos originChunk = start.getChunkPos();
            if (data.isVillageRegistered(originChunk)) {
                BoundingBox fullBox = start.getBoundingBox();
                BlockPos center = new BlockPos(fullBox.getCenter().getX(), fullBox.getCenter().getY(), fullBox.getCenter().getZ());
                VillageRecord existingVillage = data.getVillageFor(center);
                List<StructurePiece> pieces = List.copyOf(start.getPieces());
                BlockPos structureCenter = structureCenter(fullBox, pieces);
                boolean abandonedVillage = isAbandonedVillage(pieces);
                if (existingVillage != null && existingVillage.isAbandonedVillage() != abandonedVillage) {
                    existingVillage.setAbandonedVillage(abandonedVillage);
                    data.setDirty();
                }
                if (existingVillage != null) {
                    if (existingVillage.setInitialScanAnchorBounds(structureAnchorBounds(fullBox))) {
                        data.setDirty();
                    }
                    DETECTED_CONTEXTS.put(existingVillage.getVillageId(),
                            new DetectedVillageContext(structureCenter, fullBox, pieces));
                    boolean pendingBell = data.getPendingManagerPlacements().stream()
                            .anyMatch(pending -> pending.villageId().equals(existingVillage.getVillageId()));
                    boolean farmsPlaced = VillageFarmSavedData.get(serverLevel)
                            .areFarmsPlaced(structureCenter);
                    boolean libraryPlaced = data.hasGeneratedLibrary(existingVillage.getVillageId());
                    boolean lumbermillPlaced = data.hasGeneratedLumbermill(existingVillage.getVillageId());
                    if (!pendingBell
                            && (!data.hasGeneratedBank(existingVillage.getVillageId())
                            || !libraryPlaced || !lumbermillPlaced || !farmsPlaced)) {
                        enqueuePipeline(serverLevel, existingVillage.getVillageId(), fullBox, pieces,
                                existingVillage.getBellPosition());
                    }
                }
                continue;
            }

            if (!QUEUED_OR_ACTIVE_ORIGINS.add(VillageOriginKey.of(serverLevel, originChunk))) {
                continue;
            }

            BoundingBox fullBox = start.getBoundingBox();

            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Auto-detected village {} at chunk ({}, {}), bounding box [({}, {}, {}) to ({}, {}, {})]",
                    structureId.getPath(),
                    originChunk.x, originChunk.z,
                    fullBox.minX(), fullBox.minY(), fullBox.minZ(),
                    fullBox.maxX(), fullBox.maxY(), fullBox.maxZ()
            );

            // Queue for processing on next server tick to avoid recursive chunk loading
            PENDING.add(new PendingVillageRegistration(serverLevel, originChunk,
                    fullBox, List.copyOf(start.getPieces())));
        }
    }

    /**
     * Processes queued village registrations and retries pending manager placements.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;

        for (int i = 0; i < MAX_NEW_REGISTRATIONS_PER_TICK; i++) {
            PendingVillageRegistration pending = PENDING.poll();
            if (pending == null) {
                break;
            }
            ACTIVE_SEARCHES.add(new PendingVillageRegistrationTask(
                    pending.level, pending.originChunk, pending.boundingBox, pending.pieces));
        }

        int tasksToAdvance = Math.min(MAX_ACTIVE_TASKS_PER_TICK, ACTIVE_SEARCHES.size());
        for (int i = 0; i < tasksToAdvance; i++) {
            ActiveTask task = ACTIVE_SEARCHES.poll();
            if (task == null) {
                break;
            }
            boolean done = task.processStep();
            if (!done) {
                ACTIVE_SEARCHES.add(task);
            }
        }

        processPendingPoolLumbermills();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            processPendingNameAssignments(level);
            if (tickCounter % PENDING_RETRY_INTERVAL == 0) {
                retryPendingPlacements(level);
            }
        }
        VillageGenerationPipeline.processTick(event.getServer());
    }

    /**
     * Clips {@code bounds} against all existing villages so no two village AABB regions overlap.
     * For each overlapping existing village, the boundary between them is set at the midpoint
     * between the two bell positions (X and Z axes only; Y is left unchanged).
     * The existing village's bounds are also clipped symmetrically so the split is mutual.
     *
     * @param excludeId village UUID to skip (use for the village being re-registered so it
     *                  doesn't clip against its own existing placeholder record)
     */
    private static AABB clipAgainstExisting(VillageRegistryData data, BlockPos newBell, AABB bounds) {
        return clipAgainstExisting(data, newBell, bounds, null);
    }

    private static AABB clipAgainstExisting(VillageRegistryData data, BlockPos newBell, AABB bounds, @Nullable UUID excludeId) {
        double minX = bounds.minX, minY = bounds.minY, minZ = bounds.minZ;
        double maxX = bounds.maxX, maxY = bounds.maxY, maxZ = bounds.maxZ;

        for (VillageRecord existing : data.getVillages().values()) {
            if (excludeId != null && existing.getVillageId().equals(excludeId)) {
                continue;
            }
            AABB other = existing.getBoundingBox();
            // Skip if already no overlap
            if (maxX <= other.minX || minX >= other.maxX
                    || maxY <= other.minY || minY >= other.maxY
                    || maxZ <= other.minZ || minZ >= other.maxZ) {
                continue;
            }

            BlockPos existingBell = existing.getBellPosition();
            double midX = (newBell.getX() + existingBell.getX()) / 2.0;
            double midZ = (newBell.getZ() + existingBell.getZ()) / 2.0;

            // Clip new bounds
            if (newBell.getX() <= existingBell.getX()) {
                maxX = Math.min(maxX, midX);
            } else {
                minX = Math.max(minX, midX);
            }
            if (newBell.getZ() <= existingBell.getZ()) {
                maxZ = Math.min(maxZ, midZ);
            } else {
                minZ = Math.max(minZ, midZ);
            }

            // Clip existing village's bounds symmetrically
            AABB clippedOther;
            if (existingBell.getX() <= newBell.getX()) {
                clippedOther = new AABB(other.minX, other.minY, other.minZ,
                        Math.min(other.maxX, midX), other.maxY, other.maxZ);
            } else {
                clippedOther = new AABB(Math.max(other.minX, midX), other.minY, other.minZ,
                        other.maxX, other.maxY, other.maxZ);
            }
            if (existingBell.getZ() <= newBell.getZ()) {
                clippedOther = new AABB(clippedOther.minX, clippedOther.minY, clippedOther.minZ,
                        clippedOther.maxX, clippedOther.maxY, Math.min(clippedOther.maxZ, midZ));
            } else {
                clippedOther = new AABB(clippedOther.minX, clippedOther.minY, Math.max(clippedOther.minZ, midZ),
                        clippedOther.maxX, clippedOther.maxY, clippedOther.maxZ);
            }
            existing.setBoundingBox(clippedOther);
            data.setDirty();
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void finishRegistration(ServerLevel level, UUID villageId, ChunkPos originChunk,
                                           BoundingBox structureBox,
                                           @Nullable BlockPos bellPos, List<StructurePiece> pieces) {
        VillageRegistryData data = VillageRegistryData.get(level);
        boolean abandonedVillage = isAbandonedVillage(pieces);
        BlockPos structureCenter = structureCenter(structureBox, pieces);
        DETECTED_CONTEXTS.put(villageId,
                new DetectedVillageContext(structureCenter, structureBox, List.copyOf(pieces)));
        VillageFarmSavedData.get(level).markVillageDetected(structureCenter);
        if (bellPos == null) {
            // Bell chunk might not be loaded yet: register village without bell
            // and queue for pending manager placement
            BlockPos center = new BlockPos(
                    structureBox.getCenter().getX(),
                    structureBox.getCenter().getY(),
                    structureBox.getCenter().getZ()
            );
            AABB villageBounds = clipAgainstExisting(data, center, new AABB(center).inflate(128, 48, 128));
            VillageRecord village = data.getOrCreateVillage(villageId, center, villageBounds);
            village.setInitialScanAnchorBounds(structureAnchorBounds(structureBox));
            if (village.isAbandonedVillage() != abandonedVillage) {
                village.setAbandonedVillage(abandonedVillage);
                data.setDirty();
            }
            data.addPendingManagerPlacement(villageId, structureBox);
            data.markVillageRegistered(originChunk);

            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Registered village {} (bell not yet loaded, queued for placement)",
                    villageId
            );
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Deferred standard village pipeline for {} until its bell is available",
                    villageId
            );
            return;
        }

        AABB villageBounds = clipAgainstExisting(data, bellPos, new AABB(bellPos).inflate(128, 48, 128));
        VillageRecord village = data.getOrCreateVillage(villageId, bellPos, villageBounds);
        village.setInitialScanAnchorBounds(structureAnchorBounds(structureBox));
        if (village.isAbandonedVillage() != abandonedVillage) {
            village.setAbandonedVillage(abandonedVillage);
            data.setDirty();
        }
        data.markVillageRegistered(originChunk);
        EmeraldCapitalism.LOGGER.info(
                "[ECAP] Registered village {} at bell=({}, {}, {})",
                villageId, bellPos.getX(), bellPos.getY(), bellPos.getZ()
        );

        enqueuePipeline(level, villageId, structureBox, pieces, bellPos);
    }

    private static void enqueuePipeline(ServerLevel level, UUID villageId, BoundingBox structureBox,
                                        List<StructurePiece> pieces, BlockPos bellPos) {
        VillageRegistryData data = VillageRegistryData.get(level);
        VillageRecord village = data.getVillages().get(villageId);
        if (village != null && village.setInitialScanAnchorBounds(structureAnchorBounds(structureBox))) {
            data.setDirty();
        }
        boolean abandoned = village != null && village.isAbandonedVillage();
        BlockPos center = structureCenter(structureBox, pieces);
        DETECTED_CONTEXTS.put(villageId,
                new DetectedVillageContext(center, structureBox, List.copyOf(pieces)));
        VillageGenerationPipeline.enqueue(level, villageId, center, bellPos, structureBox,
                pieces, abandoned, VillageDetectionHandler::completeVillageGeneration);
    }

    private static void completeVillageGeneration(VillageGenerationContext context) {
        VillageRegistryData data = VillageRegistryData.get(context.level());
        VillageRecord village = data.getVillages().get(context.villageId());
        if (village != null) {
            if (context.pipelineCompletedSuccessfully()) {
                // Successful finalization follows bank/farm placement, paths, post-processing,
                // finish hooks, and farm completion persistence. The manager waits until the
                // pipeline leaves its active set before starting this initial scan.
                if (!village.isCacheInitialized()) {
                    VillageRegistryEvents.getManager(context.level()).requestFullScan(village);
                }
                if (Config.enableWorldgenVillageRootNaming) {
                    enqueueDelayedNameAssignment(context.level(), context.villageId());
                } else {
                    data.assignLegacyVillageNumberName(context.level(), village);
                }
            } else {
                VillageRegistryEvents.getManager(context.level())
                        .cancelDeferredInitialFullScan(context.villageId());
            }
            data.setDirty();
        }
        DETECTED_CONTEXTS.remove(context.villageId());
    }

    private static BlockPos structureCenter(BoundingBox structureBox, List<StructurePiece> pieces) {
        BoundingBox centerBox = pieces.isEmpty() ? structureBox : pieces.getFirst().getBoundingBox();
        return new BlockPos(centerBox.getCenter().getX(), centerBox.getCenter().getY(),
                centerBox.getCenter().getZ());
    }

    private static AABB structureAnchorBounds(BoundingBox structureBox) {
        return new AABB(
                structureBox.minX(), structureBox.minY(), structureBox.minZ(),
                structureBox.maxX(), structureBox.maxY(), structureBox.maxZ()
        );
    }

    /**
     * Promotes any unscanned pending placements into budgeted {@link RetryPlacementTask}s.
     * Each task is advanced a few thousand blocks per tick via {@link BellSearchCursor},
     * preventing the synchronous full-box scan that was here previously.
     */
    private static void retryPendingPlacements(ServerLevel level) {
        VillageRegistryData data = VillageRegistryData.get(level);
        List<VillageRegistryData.PendingManagerPlacement> pendingList = data.getPendingManagerPlacements();
        if (pendingList.isEmpty()) {
            return;
        }

        for (VillageRegistryData.PendingManagerPlacement pending : pendingList) {
            // add() returns false if the ID is already present: prevents double-queuing
            // a village while its RetryPlacementTask is still in ACTIVE_SEARCHES.
            if (QUEUED_RETRY_IDS.add(pending.villageId())) {
                ACTIVE_SEARCHES.add(new RetryPlacementTask(level, pending));
            }
        }
    }

    private static void finishRetryRegistration(ServerLevel level,
                                                VillageRegistryData.PendingManagerPlacement pending,
                                                BlockPos bellPos) {
        VillageRegistryData data = VillageRegistryData.get(level);

        var village = data.getVillages().get(pending.villageId());
        if (village != null) {
            // Exclude this village's own placeholder record from the overlap check
            AABB villageBounds = clipAgainstExisting(data, bellPos, new AABB(bellPos).inflate(128, 48, 128), pending.villageId());
            village.setBellPosition(bellPos);
            village.setBoundingBox(villageBounds, level);
            village.setInitialScanAnchorBounds(structureAnchorBounds(pending.structureBox()));
            data.setDirty();
        }

        data.removePendingManagerPlacement(pending);
        DetectedVillageContext detected = DETECTED_CONTEXTS.get(pending.villageId());
        List<StructurePiece> pieces = detected != null ? detected.pieces() : List.of();
        enqueuePipeline(level, pending.villageId(), pending.structureBox(), pieces, bellPos);

        EmeraldCapitalism.LOGGER.info(
                "[ECAP] Resolved pending placement for village {} at bell=({}, {}, {})",
                pending.villageId(), bellPos.getX(), bellPos.getY(), bellPos.getZ()
        );
    }

    private static void enqueueDelayedNameAssignment(ServerLevel level, UUID villageId) {
        PENDING_NAME_ASSIGNMENTS.add(new PendingNameAssignment(
                level, villageId, tickCounter + NAME_ASSIGNMENT_DELAY_TICKS, null));
    }

    private static void processPendingNameAssignments(ServerLevel level) {
        if (PENDING_NAME_ASSIGNMENTS.isEmpty()) {
            return;
        }
        VillageRegistryData data = VillageRegistryData.get(level);
        int budget = 8;
        while (budget-- > 0) {
            PendingNameAssignment pending = PENDING_NAME_ASSIGNMENTS.peek();
            if (pending == null) {
                return;
            }
            if (pending.executeAtTick() > tickCounter) {
                return;
            }
            PENDING_NAME_ASSIGNMENTS.poll();
            if (pending.level() != level) {
                PENDING_NAME_ASSIGNMENTS.add(pending);
                continue;
            }
            VillageRecord village = data.getVillages().get(pending.villageId());
            if (village == null) {
                continue;
            }
            if (!village.isCacheInitialized()) {
                VillageRegistryEvents.getManager(level).requestFullScan(village);
                PENDING_NAME_ASSIGNMENTS.add(new PendingNameAssignment(
                        level, pending.villageId(), tickCounter + NAME_ASSIGNMENT_DELAY_TICKS,
                        pending.assignedName()));
                continue;
            }
            String villageName = pending.assignedName();
            if (villageName == null) {
                var generatedVillageName = WORLDGEN_NAME_ASSIGNER.assignGeneratedName(level, village);
                if (generatedVillageName.isEmpty()) {
                    // Resource reloads can temporarily leave the canonical lexicon
                    // unavailable. Keep the assignment alive instead of leaving the
                    // village with its fallback name forever.
                    PENDING_NAME_ASSIGNMENTS.add(new PendingNameAssignment(
                            level, pending.villageId(), tickCounter + NAME_ASSIGNMENT_DELAY_TICKS, null));
                    continue;
                }
                villageName = generatedVillageName.get();
                data.setDirty();
            }

            // The manager and bank may be in an unloaded chunk when the player
            // teleports away. Retain the generated name and retry after those
            // chunks load so the bank does not keep its order-dependent fallback
            // name ("Bank 2", "Bank 3", ...).
            if (!renameVillageBankIfPresent(level, village, villageName)) {
                PENDING_NAME_ASSIGNMENTS.add(new PendingNameAssignment(
                        level, pending.villageId(), tickCounter + NAME_ASSIGNMENT_DELAY_TICKS, villageName));
            } else {
                data.setDirty();
            }
        }
    }

    private static boolean renameVillageBankIfPresent(ServerLevel level, VillageRecord village, String villageName) {
        BlockPos vmPos = VillageRegistryData.get(level).getVMPos(village.getVillageId());
        if (vmPos == null || !level.isLoaded(vmPos)) {
            return false;
        }

        if (!(level.getBlockEntity(vmPos) instanceof VillageManagerBlockEntity vm)) {
            return false;
        }

        BlockPos bankPos = vm.getBankPos();
        if (bankPos == null || !level.isLoaded(bankPos)) {
            return false;
        }

        if (!(level.getBlockEntity(bankPos) instanceof BankBlockEntity bank)) {
            return false;
        }

        String expectedBankName = "Bank of " + villageName;
        if (expectedBankName.equals(bank.getBankName())) {
            return true;
        }

        bank.setBankName(expectedBankName);
        EmeraldCapitalism.LOGGER.info(
                "[ECAP] Renamed bank at {} for village {} to '{}'",
                bankPos,
                village.getVillageId(),
                expectedBankName
        );
        return true;
    }

    private static boolean isVillageStructure(ResourceLocation structureId) {
        if (!structureId.getNamespace().equals("minecraft")) {
            return false;
        }
        return switch (structureId.getPath()) {
            case "village_plains", "village_desert", "village_savanna",
                 "village_taiga", "village_snowy" -> true;
            default -> false;
        };
    }

    /** Queues custom lumbermills selected by the normal vanilla village house pool. */
    private static void queuePoolLumbermillPopulation(ServerLevel level, StructurePiece piece) {
        if (!(piece instanceof PoolElementStructurePiece poolPiece)
                || !isPoolLumbermill(poolPiece)) {
            return;
        }
        long structureKey = poolPiece.getPosition().asLong();
        VillageRegistryData data = VillageRegistryData.get(level);
        if (data.hasGeneratedLumbermillStructure(structureKey)) {
            return;
        }
        PoolLumbermillKey key = new PoolLumbermillKey(level, structureKey);
        PendingPoolLumbermill pending = new PendingPoolLumbermill(
                level, structureKey, piece.getBoundingBox());
        PENDING_POOL_LUMBERMILLS.putIfAbsent(key, pending);
        if (tryPopulatePoolLumbermill(pending)) {
            PENDING_POOL_LUMBERMILLS.remove(key, pending);
        }
    }

    private static boolean isPoolLumbermill(PoolElementStructurePiece piece) {
        String element = piece.getElement().toString().toLowerCase(Locale.ROOT);
        return element.contains("emeraldcapitalism:village/")
                && element.contains("lumbermill_");
    }

    private static void processPendingPoolLumbermills() {
        int processed = 0;
        for (Map.Entry<PoolLumbermillKey, PendingPoolLumbermill> entry
                : new ArrayList<>(PENDING_POOL_LUMBERMILLS.entrySet())) {
            if (processed++ >= 4) {
                break;
            }
            PendingPoolLumbermill pending = entry.getValue();
            if (tryPopulatePoolLumbermill(pending)) {
                PENDING_POOL_LUMBERMILLS.remove(entry.getKey(), pending);
            }
        }
    }

    private static boolean tryPopulatePoolLumbermill(PendingPoolLumbermill pending) {
        BoundingBox box = pending.placementBox();
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                if (!pending.level().getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }

        VillageRegistryData data = VillageRegistryData.get(pending.level());
        if (data.hasGeneratedLumbermillStructure(pending.structureKey())) {
            return true;
        }
        int villagers = LUMBERMILL_PLACER.spawnVillagers(pending.level(), box);
        if (villagers < 2) {
            return false;
        }
        data.markLumbermillStructureGenerated(pending.structureKey());
        EmeraldCapitalism.LOGGER.info(
                "[ECAP] Populated pool-generated lumbermill at {} with two villagers",
                box.getCenter());
        return true;
    }

    /**
     * Vanilla abandoned villages are assembled from the zombie sub-pools rather
     * than exposed as a separate structure ID. Their pool element locations carry
     * the stable {@code /zombie/} path marker, which is safer than checking whether
     * a zombie villager happens to still be alive when the chunk loads.
     */
    private static boolean isAbandonedVillage(List<StructurePiece> pieces) {
        for (StructurePiece piece : pieces) {
            if (piece instanceof PoolElementStructurePiece poolPiece
                    && poolPiece.getElement().toString().contains("/zombie/")) {
                return true;
            }
        }
        return false;
    }
}
