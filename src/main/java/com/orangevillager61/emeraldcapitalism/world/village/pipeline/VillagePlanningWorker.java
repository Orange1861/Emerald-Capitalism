package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrain;
import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrainSnapshot;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
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

    /**
     * A building-planning task whose search runs as one job. With the village's
     * terrain snapshot the job runs on the worker against it; without one, as
     * when planning outside the pipeline, it runs immediately on the calling
     * server thread against the live level. The job must read only the terrain
     * it is given and values the caller computed beforehand.
     */
    static VillageBuildingPlanningTask planningTask(
            VillageGenerationContext context, String description,
            Function<VillageTerrain, List<? extends PlannedVillageBuilding>> job) {
        VillageTerrainSnapshot snapshot = context.terrainSnapshot();
        CompletableFuture<List<? extends PlannedVillageBuilding>> future = snapshot != null
                ? submit(() -> job.apply(snapshot))
                : CompletableFuture.completedFuture(job.apply(VillageTerrain.live(context.level())));
        return new VillageBuildingPlanningTask() {
            private List<? extends PlannedVillageBuilding> result;

            @Override
            public boolean processStep() {
                if (result != null) {
                    return true;
                }
                if (!future.isDone()) {
                    return false;
                }
                try {
                    result = future.join();
                } catch (RuntimeException exception) {
                    EmeraldCapitalism.LOGGER.error("[ECAP] {} planning failed for village {}",
                            description, context.villageId(), exception);
                    result = List.of();
                }
                return true;
            }

            @Override
            public List<? extends PlannedVillageBuilding> result() {
                return result == null ? List.of() : result;
            }
        };
    }

    /** Drops queued jobs; a job already running finishes and its result is discarded. */
    static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
