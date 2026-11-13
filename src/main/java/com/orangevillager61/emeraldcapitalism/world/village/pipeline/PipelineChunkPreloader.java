package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads a village pipeline's chunk rectangle without blocking the server thread.
 *
 * <p>Each chunk receives a radius-0 region ticket (level 33: FULL, not block-
 * or entity-ticking), which makes the chunk map load or generate it on worker
 * threads. Later ticks poll {@code getChunkNow}. {@code getChunkFuture} is not
 * used because on the server thread it blocks until the chunk is complete, and
 * {@code hasChunk} is not used because it only checks the ticket level. Tickets
 * are held until {@link #releaseAll()} so later pipeline phases find every
 * chunk already loaded and never fall back to a blocking {@code level.getChunk}.</p>
 */
final class PipelineChunkPreloader {
    private static final TicketType<UUID> TICKET_TYPE = TicketType.create(
            "ecap_village_pipeline",
            Comparator.<UUID>naturalOrder()
    );
    private static final int TICKET_RADIUS = 0;
    /**
     * Unfinished asynchronous loads allowed at once for one village. Loads run on
     * the world-generation threads, so a wider window only adds parallel worldgen
     * work, not server-thread time.
     */
    private static final int MAX_OUTSTANDING_LOADS = 32;
    private static final int LOAD_TIMEOUT_TICKS = 400;

    private final ServerLevel level;
    private final UUID villageId;
    private final List<ChunkPos> pendingChunks = new ArrayList<>();
    /** Chunks still waiting to reach FULL, mapped to the game time their ticket was added. */
    private final Map<ChunkPos, Long> outstanding = new HashMap<>();
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
     * ticketed loads.
     *
     * @return true when every chunk is loaded or has failed
     */
    boolean step(int maxNewRequestsPerTick, PreloadStepStats stats) {
        if (released) {
            return true;
        }
        long pollStarted = PerformanceTimingCounters.startTiming(
                PerformanceTimingCounters.Operation.VILLAGE_PRELOAD_POLL);
        pollOutstanding();
        stats.pollNanos += PreloadStepStats.finish(
                PerformanceTimingCounters.Operation.VILLAGE_PRELOAD_POLL, pollStarted);

        int started = 0;
        while (nextPending < pendingChunks.size()
                && started < maxNewRequestsPerTick
                && outstanding.size() < MAX_OUTSTANDING_LOADS) {
            ChunkPos pos = pendingChunks.get(nextPending++);
            long ticketStarted = PerformanceTimingCounters.startTiming(
                    PerformanceTimingCounters.Operation.VILLAGE_PRELOAD_ADD_TICKET);
            addTicket(pos);
            stats.ticketNanos += PreloadStepStats.finish(
                    PerformanceTimingCounters.Operation.VILLAGE_PRELOAD_ADD_TICKET, ticketStarted);
            stats.ticketsAdded++;
            if (isFullyLoaded(pos)) {
                // Already loaded: the ticket only keeps it loaded for later phases.
                stats.alreadyLoaded++;
                continue;
            }
            outstanding.put(pos, level.getGameTime());
            started++;
            stats.futuresStarted++;
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
        Iterator<Map.Entry<ChunkPos, Long>> iterator = outstanding.entrySet().iterator();
        long now = level.getGameTime();
        while (iterator.hasNext()) {
            Map.Entry<ChunkPos, Long> entry = iterator.next();
            ChunkPos pos = entry.getKey();
            if (isFullyLoaded(pos)) {
                iterator.remove();
            } else if (now - entry.getValue() >= LOAD_TIMEOUT_TICKS) {
                // The ticket stays in `ticketed` and is removed by releaseAll().
                failedChunks++;
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Timed out loading chunk ({}, {}) for village pipeline {}",
                        pos.x, pos.z, villageId);
                iterator.remove();
            }
        }
    }

    /** Non-blocking FULL check; unlike hasChunk it requires the chunk itself to be present. */
    private boolean isFullyLoaded(ChunkPos pos) {
        return level.getChunkSource().getChunkNow(pos.x, pos.z) != null;
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
}
