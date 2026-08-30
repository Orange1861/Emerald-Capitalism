package com.orangevillager61.emeraldcapitalism.world.village.scan;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Level-wide asynchronous chunk-load pool used only by adaptive initial village scans.
 * Requests retain a temporary ticket until their chunk has been scanned or the scan is cancelled.
 */
public final class InitialVillageScanChunkLoadPool implements AutoCloseable {
    private static final int LOAD_TIMEOUT_TICKS = 200;
    private static final int TICKET_RADIUS = 0;
    private static final TicketType<UUID> TICKET_TYPE = TicketType.create(
            "improved_villagers_initial_scan",
            Comparator.<UUID>naturalOrder()
    );

    public enum Availability {
        AVAILABLE,
        WAITING,
        BATCH_LIMIT,
        REFUSED
    }

    private final ServerLevel level;
    private final PermitTracker permits = new PermitTracker();
    private final Map<RequestKey, ActiveLoad> activeLoads = new HashMap<>();

    public InitialVillageScanChunkLoadPool(ServerLevel level) {
        this.level = level;
    }

    /**
     * Returns the state of the requested chunk without blocking the server thread.
     * A WAITING result means the caller should retain its scan cursor and retry on a later tick.
     */
    public Availability ensureAvailable(UUID villageId, int chunkX, int chunkZ) {
        RequestKey key = new RequestKey(villageId, chunkX, chunkZ);
        if (level.hasChunk(chunkX, chunkZ)) {
            return Availability.AVAILABLE;
        }

        ActiveLoad existing = activeLoads.get(key);
        if (existing != null) {
            return pollExisting(key, existing);
        }

        PermitResult permitResult = permits.tryAcquire(
                villageId,
                Config.villageInitialScanChunkLoadPoolSize,
                Config.villageInitialScanChunkLoadCapPerVillage
        );
        if (permitResult == PermitResult.GLOBAL_LIMIT) {
            return Config.villageInitialScanChunkLoadPoolSize <= 0
                    ? Availability.REFUSED
                    : Availability.WAITING;
        }
        if (permitResult == PermitResult.SCAN_LIMIT) {
            return Config.villageInitialScanChunkLoadCapPerVillage <= 0
                    ? Availability.REFUSED
                    : Availability.BATCH_LIMIT;
        }

        return startLoad(key) ? Availability.WAITING : Availability.REFUSED;
    }

    /** Requests a small ordered window without blocking or exceeding either configured limit. */
    public void prefetch(UUID villageId, Collection<AdaptiveChunkScanPlan.ChunkCoordinate> chunks) {
        if (Config.villageInitialScanChunkLoadPoolSize <= 0
                || Config.villageInitialScanChunkLoadCapPerVillage <= 0) {
            return;
        }
        for (AdaptiveChunkScanPlan.ChunkCoordinate chunk : chunks) {
            RequestKey key = new RequestKey(villageId, chunk.x(), chunk.z());
            if (level.hasChunk(chunk.x(), chunk.z()) || activeLoads.containsKey(key)) {
                continue;
            }
            PermitResult permitResult = permits.tryAcquire(
                    villageId,
                    Config.villageInitialScanChunkLoadPoolSize,
                    Config.villageInitialScanChunkLoadCapPerVillage
            );
            if (permitResult != PermitResult.ACQUIRED) {
                return;
            }
            if (!startLoad(key)) {
                return;
            }
        }
    }

    /** Starts another request batch without discarding the scan cursor or collected results. */
    public boolean beginNextBatch(UUID villageId) {
        return permits.beginNextBatch(villageId);
    }

    /** Releases a temporary ticket after its chunk cursor advances. */
    public void release(UUID villageId, int chunkX, int chunkZ) {
        RequestKey key = new RequestKey(villageId, chunkX, chunkZ);
        ActiveLoad load = activeLoads.remove(key);
        if (load == null) {
            return;
        }
        removeTicket(key, load);
        permits.releaseActive(villageId);
    }

    /** Cancels all outstanding loads and resets the current batch for this village. */
    public void finishScan(UUID villageId) {
        Iterator<Map.Entry<RequestKey, ActiveLoad>> iterator = activeLoads.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RequestKey, ActiveLoad> entry = iterator.next();
            if (!entry.getKey().villageId().equals(villageId)) {
                continue;
            }
            removeTicket(entry.getKey(), entry.getValue());
            permits.releaseActive(villageId);
            iterator.remove();
        }
        permits.finishScan(villageId);
    }

    @Override
    public void close() {
        for (Map.Entry<RequestKey, ActiveLoad> entry : activeLoads.entrySet()) {
            removeTicket(entry.getKey(), entry.getValue());
        }
        activeLoads.clear();
        permits.clear();
    }

    private Availability pollExisting(RequestKey key, ActiveLoad load) {
        if (level.getGameTime() - load.startedTick() >= LOAD_TIMEOUT_TICKS) {
            release(key.villageId(), key.chunkX(), key.chunkZ());
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Timed out loading temporary chunk ({}, {}) for initial village scan {}",
                    key.chunkX(),
                    key.chunkZ(),
                    key.villageId()
            );
            return Availability.REFUSED;
        }
        if (!load.future().isDone()) {
            return Availability.WAITING;
        }

        try {
            ChunkResult<ChunkAccess> result = load.future().getNow(null);
            if (result != null && result.isSuccess() && level.hasChunk(key.chunkX(), key.chunkZ())) {
                return Availability.AVAILABLE;
            }
        } catch (RuntimeException exception) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Temporary chunk load failed for ({}, {}) in initial village scan {}",
                    key.chunkX(),
                    key.chunkZ(),
                    key.villageId(),
                    exception
            );
        }

        release(key.villageId(), key.chunkX(), key.chunkZ());
        return Availability.REFUSED;
    }

    private boolean startLoad(RequestKey key) {
        ChunkPos chunkPos = new ChunkPos(key.chunkX(), key.chunkZ());
        level.getChunkSource().addRegionTicket(TICKET_TYPE, chunkPos, TICKET_RADIUS, key.villageId());
        try {
            CompletableFuture<ChunkResult<ChunkAccess>> future = level.getChunkSource()
                    .getChunkFuture(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, true);
            activeLoads.put(key, new ActiveLoad(chunkPos, future, level.getGameTime()));
            return true;
        } catch (RuntimeException exception) {
            level.getChunkSource().removeRegionTicket(TICKET_TYPE, chunkPos, TICKET_RADIUS, key.villageId());
            permits.rollbackAcquire(key.villageId());
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Failed to request temporary chunk ({}, {}) for initial village scan {}",
                    key.chunkX(),
                    key.chunkZ(),
                    key.villageId(),
                    exception
            );
            return false;
        }
    }

    private void removeTicket(RequestKey key, ActiveLoad load) {
        level.getChunkSource().removeRegionTicket(
                TICKET_TYPE,
                load.chunkPos(),
                TICKET_RADIUS,
                key.villageId()
        );
    }

    private record RequestKey(UUID villageId, int chunkX, int chunkZ) {
    }

    private record ActiveLoad(
            ChunkPos chunkPos,
            CompletableFuture<ChunkResult<ChunkAccess>> future,
            long startedTick
    ) {
    }

    enum PermitResult {
        ACQUIRED,
        GLOBAL_LIMIT,
        SCAN_LIMIT
    }

    /** Pure admission accounting, kept separate so both caps can be unit-tested without a server. */
    static final class PermitTracker {
        private final Map<UUID, Integer> startedPerBatch = new HashMap<>();
        private final Map<UUID, Integer> activePerVillage = new HashMap<>();
        private int active;

        PermitResult tryAcquire(UUID villageId, int globalLimit, int perScanLimit) {
            if (perScanLimit <= 0 || startedPerBatch.getOrDefault(villageId, 0) >= perScanLimit) {
                return PermitResult.SCAN_LIMIT;
            }
            if (globalLimit <= 0 || active >= globalLimit) {
                return PermitResult.GLOBAL_LIMIT;
            }
            active++;
            activePerVillage.merge(villageId, 1, Integer::sum);
            startedPerBatch.merge(villageId, 1, Integer::sum);
            return PermitResult.ACQUIRED;
        }

        void releaseActive(UUID villageId) {
            if (active <= 0) {
                throw new IllegalStateException("Released more initial-scan chunk-load permits than acquired");
            }
            active--;
            int villageActive = activePerVillage.getOrDefault(villageId, 0);
            if (villageActive <= 0) {
                throw new IllegalStateException("Released a missing initial-scan village permit");
            }
            if (villageActive == 1) {
                activePerVillage.remove(villageId);
            } else {
                activePerVillage.put(villageId, villageActive - 1);
            }
        }

        void rollbackAcquire(UUID villageId) {
            releaseActive(villageId);
            int started = startedPerBatch.getOrDefault(villageId, 0);
            if (started <= 1) {
                startedPerBatch.remove(villageId);
            } else {
                startedPerBatch.put(villageId, started - 1);
            }
        }

        boolean beginNextBatch(UUID villageId) {
            if (activePerVillage.getOrDefault(villageId, 0) != 0) {
                return false;
            }
            startedPerBatch.remove(villageId);
            return true;
        }

        void finishScan(UUID villageId) {
            startedPerBatch.remove(villageId);
            activePerVillage.remove(villageId);
        }

        void clear() {
            active = 0;
            startedPerBatch.clear();
            activePerVillage.clear();
        }

        int active() {
            return active;
        }

        int started(UUID villageId) {
            return startedPerBatch.getOrDefault(villageId, 0);
        }
    }
}
