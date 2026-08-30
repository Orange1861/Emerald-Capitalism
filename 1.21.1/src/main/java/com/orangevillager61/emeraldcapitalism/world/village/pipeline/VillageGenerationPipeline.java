package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.util.SharedScanGenerationBudget;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingOrder;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The only coordinator for post-vanilla village additions.
 *
 * <p>Lifecycle: shared caches -> provider planning/reservations -> manager -> buildings
 * -> all connectors -> provider post-processing -> naming/persistence. Building code must
 * plug into {@link VillageBuildingRegistry}; it must not create another event listener.</p>
 */
public final class VillageGenerationPipeline {
    private static final int CACHE_CHUNKS_PER_TICK = 16;
    private static final ConcurrentLinkedQueue<PipelineTask> PENDING = new ConcurrentLinkedQueue<>();
    private static final Set<PipelineKey> QUEUED_OR_ACTIVE = ConcurrentHashMap.newKeySet();
    private static PipelineTask activeTask;

    static {
        VillageBuildingRegistry.register(new BankVillageBuildingProvider());
        VillageBuildingRegistry.register(new LumbermillVillageBuildingProvider());
        VillageBuildingRegistry.register(new LibraryVillageBuildingProvider());
        VillageBuildingRegistry.register(new FarmVillageBuildingProvider());
    }

    private VillageGenerationPipeline() {
    }

    public static boolean enqueue(ServerLevel level, UUID villageId, BlockPos structureCenter,
                                  BlockPos bellPos, BoundingBox villageBox,
                                  List<StructurePiece> pieces, boolean abandonedVillage,
                                  Consumer<VillageGenerationContext> completion) {
        PipelineKey key = new PipelineKey(level, villageId);
        if (!QUEUED_OR_ACTIVE.add(key)) {
            return false;
        }
        VillageGenerationContext context = new VillageGenerationContext(
                level, villageId, structureCenter, bellPos, villageBox, pieces, abandonedVillage);
        PENDING.add(new PipelineTask(key, context, completion));
        return true;
    }

    /** True while bank, farm, path, or finalization work is still queued or active. */
    public static boolean isQueuedOrActive(ServerLevel level, UUID villageId) {
        return QUEUED_OR_ACTIVE.contains(new PipelineKey(level, villageId));
    }

    /** Advances one village task per tick so different villages cannot stack generation spikes. */
    public static void processTick(MinecraftServer server) {
        if (activeTask == null) {
            activeTask = pollLiveTask(server);
        }
        if (activeTask == null) {
            return;
        }
        if (!SharedScanGenerationBudget.tryAcquire(server,
                SharedScanGenerationBudget.WorkType.GENERATION)) {
            return;
        }
        boolean finished = PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.VILLAGE_GENERATION,
                activeTask::processStep);
        if (finished) {
            QUEUED_OR_ACTIVE.remove(activeTask.key);
            activeTask = null;
        }
    }

    public static void clearPendingWork() {
        PENDING.clear();
        QUEUED_OR_ACTIVE.clear();
        activeTask = null;
        SharedScanGenerationBudget.clearAll();
    }

    private static PipelineTask pollLiveTask(MinecraftServer server) {
        PipelineTask task;
        while ((task = PENDING.poll()) != null) {
            if (task.context.level().getServer() == server) {
                return task;
            }
            QUEUED_OR_ACTIVE.remove(task.key);
        }
        return null;
    }

    private enum Phase {
        BUILD_CACHES,
        PLAN_BUILDINGS,
        PLACE_MANAGER,
        PLACE_BUILDINGS,
        GENERATE_PATHS,
        PREPARE_POST_PROCESS,
        POST_PROCESS,
        SPAWN_AND_FINISH,
        FINALIZE
    }

    private static final class PipelineTask {
        private static final Comparator<PlannedVillageBuilding> BUILDING_ORDER = Comparator.comparing(
                PipelineTask::buildingOrderKey,
                VillageBuildingOrder.planComparator());

        private final PipelineKey key;
        private final VillageGenerationContext context;
        private final Consumer<VillageGenerationContext> completion;
        private final List<VillageBuildingProvider> providers = VillageBuildingRegistry.orderedProviders();
        private final List<PlannedVillageBuilding> plans = new ArrayList<>();
        private final List<PlannedVillageBuilding> placedBuildings = new ArrayList<>();
        private final List<VillageRoadPathGenerator.PlannedPath> paths = new ArrayList<>();
        private final List<VillagePostProcessTask> postTasks = new ArrayList<>();
        private Phase phase = Phase.BUILD_CACHES;
        private int index;
        private int ticksUsed;

        private static VillageBuildingOrder.PlanKey buildingOrderKey(PlannedVillageBuilding plan) {
            BoundingBox box = plan.reservationBox();
            return new VillageBuildingOrder.PlanKey(plan.importance(), plan.footprintArea(),
                    plan.providerId().toString(), box.minX(), box.minZ());
        }

        private PipelineTask(PipelineKey key, VillageGenerationContext context,
                             Consumer<VillageGenerationContext> completion) {
            this.key = key;
            this.context = context;
            this.completion = completion;
        }

        private boolean processStep() {
            ticksUsed++;
            try {
                return switch (phase) {
                    case BUILD_CACHES -> buildCaches();
                    case PLAN_BUILDINGS -> planBuildings();
                    case PLACE_MANAGER -> placeManager();
                    case PLACE_BUILDINGS -> placeBuildings();
                    case GENERATE_PATHS -> generatePaths();
                    case PREPARE_POST_PROCESS -> preparePostProcessing();
                    case POST_PROCESS -> postProcess();
                    case SPAWN_AND_FINISH -> spawnAndFinish();
                    case FINALIZE -> finalizeTask();
                };
            } catch (Exception exception) {
                EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Village generation pipeline failed for {} in phase {}",
                        context.villageId(), phase, exception);
                completion.accept(context);
                return true;
            }
        }

        private boolean buildCaches() {
            if (context.buildSharedCachesStep(CACHE_CHUNKS_PER_TICK)) {
                phase = Phase.PLAN_BUILDINGS;
            }
            return false;
        }

        /** One provider per tick; importance then maximum size determines reservation order. */
        private boolean planBuildings() {
            if (index < providers.size()) {
                VillageBuildingProvider provider = providers.get(index++);
                List<? extends PlannedVillageBuilding> candidates = provider.plan(context);
                candidates.stream().sorted(BUILDING_ORDER).forEach(plan -> {
                    if (!plan.providerId().equals(provider.id())) {
                        EmeraldCapitalism.LOGGER.warn(
                                "[ECAP] Provider {} returned plan owned by {}; skipped",
                                provider.id(), plan.providerId());
                        return;
                    }
                    if (!context.reservations().reserveBuilding(
                            plan.providerId(), plan.placementBox(), plan.reservationBox())) {
                        EmeraldCapitalism.LOGGER.debug(
                                "[ECAP] Rejected overlapping {} plan at {}",
                                plan.providerId(), plan.reservationBox());
                        return;
                    }
                    for (VillageRoadPathGenerator.PlannedPath path : plan.reservedPaths()) {
                        context.reservations().reservePath(path.reservedSurfaceCells());
                    }
                    plans.add(plan);
                });
                if (index >= providers.size()) {
                    finishPlanning();
                }
                return false;
            }
            finishPlanning();
            return false;
        }

        private void finishPlanning() {
            plans.sort(BUILDING_ORDER);
            context.freezeFinalRoadObstacles();
            index = 0;
            phase = Phase.PLACE_MANAGER;
        }

        private boolean placeManager() {
            context.placeManager();
            phase = Phase.PLACE_BUILDINGS;
            return false;
        }

        /** One structure per tick; important and large structures are always first. */
        private boolean placeBuildings() {
            if (index < plans.size()) {
                PlannedVillageBuilding plan = plans.get(index++);
                if (plan.place(context)) {
                    placedBuildings.add(plan);
                }
                if (index >= plans.size()) {
                    index = 0;
                    phase = Phase.GENERATE_PATHS;
                }
                return false;
            }
            index = 0;
            phase = Phase.GENERATE_PATHS;
            return false;
        }

        /** Plans and places one building's connectors per tick after all structures exist. */
        private boolean generatePaths() {
            if (index < placedBuildings.size()) {
                PlannedVillageBuilding building = placedBuildings.get(index++);
                List<VillageRoadPathGenerator.PlannedPath> buildingPaths =
                        building.pathsAfterPlacement(context);
                paths.addAll(buildingPaths);
                for (VillageRoadPathGenerator.PlannedPath path : buildingPaths) {
                    context.roadGenerator().place(context.level(), path);
                }
                if (index >= placedBuildings.size()) {
                    index = 0;
                    phase = Phase.PREPARE_POST_PROCESS;
                }
                return false;
            }
            index = 0;
            phase = Phase.PREPARE_POST_PROCESS;
            return false;
        }

        private boolean preparePostProcessing() {
            for (VillageBuildingProvider provider : providers) {
                VillagePostProcessTask task = provider.createPostProcessTask(context,
                        placedBuildings.stream()
                                .filter(plan -> plan.providerId().equals(provider.id()))
                                .toList());
                if (task != null) {
                    postTasks.add(task);
                }
            }
            phase = Phase.POST_PROCESS;
            return false;
        }

        private boolean postProcess() {
            if (index < postTasks.size()) {
                if (postTasks.get(index).processStep()) {
                    index++;
                    if (index >= postTasks.size()) {
                        phase = Phase.SPAWN_AND_FINISH;
                    }
                }
                return false;
            }
            phase = Phase.SPAWN_AND_FINISH;
            return false;
        }

        private boolean spawnAndFinish() {
            // Finish hooks must be small. Multi-tick terrain or scan work belongs in
            // createPostProcessTask(), which has already completed above.
            for (PlannedVillageBuilding building : placedBuildings) {
                building.finish(context);
            }
            context.spawnManagerResident();
            phase = Phase.FINALIZE;
            return false;
        }

        private boolean finalizeTask() {
            context.farmSavedData().markFarmsPlaced(context.structureCenter());
            context.registryData().setDirty();
            context.markPipelineCompletedSuccessfully();
            completion.accept(context);
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP] Standard village pipeline for {} completed in {} active ticks "
                            + "({} buildings, {} paths)",
                    context.villageId(), ticksUsed, placedBuildings.size(), paths.size());
            return true;
        }
    }

    private record PipelineKey(ServerLevel level, UUID villageId) {
    }
}
