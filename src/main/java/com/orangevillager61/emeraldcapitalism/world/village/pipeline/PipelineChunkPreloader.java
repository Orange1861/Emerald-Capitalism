package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Loads a village pipeline's chunk rectangle without blocking the server thread.
 *
 * <p>Each chunk receives a non-ticking region ticket (same form as
 * {@code InitialVillageScanChunkLoadPool}) and an asynchronous FULL-status
 * request. Later ticks poll the futures. Tickets are held until
 * {@link #releaseAll()} so later pipeline phases find every chunk already
 * loaded and never fall back to a blocking {@code level.getChunk}.</p>
 */
final class PipelineChunkPreloader {
    private static final TicketType<UUID> TICKET_TYPE = TicketType.create(
            "ecap_village_pipeline",
            Comparator.<UUID>naturalOrder()
    );
    private static final int TICKET_RADIUS = 0;
    /** Unfinished asynchronous loads allowed at once for one village. */
    private static final int MAX_OUTSTANDING_LOADS = 8;
    private static final int LOAD_TIMEOUT_TICKS = 400;

    private final ServerLevel level;
    private final UUID villageId;
    private final List<ChunkPos> pendingChunks = new ArrayList<>();
    private final Map<ChunkPos, PendingLoad> outstanding = new HashMap<>();
    private final List<ChunkPos> ticketed = new ArrayList<>();
    private int nextPending;
    private int failedChunks;
    private boolean released;

    PipelineChunkPreloader(ServerLevel level, UUID villageId,
                           int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        this.level = level;
        this.villageId = villageId;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                pendingChunks.add(new ChunkPos(x, z));
            }
        }
    }

    /**
     * Advances the preload by at most {@code maxNewRequestsPerTick} new
     * asynchronous requests.
     *
     * @return true when every chunk is loaded or has failed
     */
    boolean step(int maxNewRequestsPerTick) {
        if (released) {
            return true;
        }
        pollOutstanding();

        int started = 0;
        while (nextPending < pendingChunks.size()
                && started < maxNewRequestsPerTick
                && outstanding.size() < MAX_OUTSTANDING_LOADS) {
            ChunkPos pos = pendingChunks.get(nextPending++);
            addTicket(pos);
            if (level.hasChunk(pos.x, pos.z)) {
                // Already loaded: the ticket only keeps it loaded for later phases.
                continue;
            }
            try {
                CompletableFuture<ChunkResult<ChunkAccess>> future = level.getChunkSource()
                        .getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
                outstanding.put(pos, new PendingLoad(future, level.getGameTime()));
                started++;
            } catch (RuntimeException exception) {
                failedChunks++;
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Failed to request chunk ({}, {}) for village pipeline {}",
                        pos.x, pos.z, villageId, exception);
            }
        }

        boolean done = nextPending >= pendingChunks.size() && outstanding.isEmpty();
        if (done && failedChunks > 0) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Village pipeline {} preloaded with {} of {} chunks unavailable",
                    villageId, failedChunks, pendingChunks.size());
        }
        return done;
    }

    private void pollOutstanding() {
        Iterator<Map.Entry<ChunkPos, PendingLoad>> iterator = outstanding.entrySet().iterator();
        long now = level.getGameTime();
        while (iterator.hasNext()) {
            Map.Entry<ChunkPos, PendingLoad> entry = iterator.next();
            ChunkPos pos = entry.getKey();
            PendingLoad load = entry.getValue();
            if (!load.future().isDone()) {
                if (now - load.startedTick() >= LOAD_TIMEOUT_TICKS) {
                    failedChunks++;
                    EmeraldCapitalism.LOGGER.warn(
                            "[ECAP] Timed out loading chunk ({}, {}) for village pipeline {}",
                            pos.x, pos.z, villageId);
                    iterator.remove();
                }
                continue;
            }
            boolean loaded = false;
            try {
                ChunkResult<ChunkAccess> result = load.future().getNow(null);
                loaded = result != null && result.isSuccess() && level.hasChunk(pos.x, pos.z);
            } catch (RuntimeException exception) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Chunk load failed for ({}, {}) in village pipeline {}",
                        pos.x, pos.z, villageId, exception);
            }
            if (!loaded) {
                failedChunks++;
            }
            iterator.remove();
        }
    }

    private void addTicket(ChunkPos pos) {
        level.getChunkSource().addRegionTicket(TICKET_TYPE, pos, TICKET_RADIUS, villageId);
        ticketed.add(pos);
    }

    /** Removes every ticket this preloader added. Safe to call more than once. */
    void releaseAll() {
        if (released) {
            return;
        }
        released = true;
        for (ChunkPos pos : ticketed) {
            level.getChunkSource().removeRegionTicket(TICKET_TYPE, pos, TICKET_RADIUS, villageId);
        }
        ticketed.clear();
        outstanding.clear();
    }

    private record PendingLoad(CompletableFuture<ChunkResult<ChunkAccess>> future, long startedTick) {
    }
}
