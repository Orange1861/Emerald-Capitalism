package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * One background thread for village site and connector planning.
 *
 * <p>Jobs read only immutable inputs and a {@link com.orangevillager61.emeraldcapitalism.world.village.VillageTerrainSnapshot}
 * that no thread writes while a job runs; they never touch a level. Submitting
 * a job publishes everything the server thread prepared before it, and the
 * returned future publishes the result back. A single thread keeps planning for
 * several villages from competing for CPU and runs jobs in submission order.</p>
 *
 * <p>The thread is created on first use and shut down with pending village work
 * when the server stops or starts, so no job or reference outlives a server.</p>
 */
final class VillagePlanningWorker {
    private static ExecutorService executor;

    private VillagePlanningWorker() {
    }

    static synchronized <T> CompletableFuture<T> submit(Supplier<T> job) {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ECAP village planner");
                thread.setDaemon(true);
                return thread;
            });
        }
        return CompletableFuture.supplyAsync(job, executor);
    }

    /** Drops queued jobs; a job already running finishes and its result is discarded. */
    static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
