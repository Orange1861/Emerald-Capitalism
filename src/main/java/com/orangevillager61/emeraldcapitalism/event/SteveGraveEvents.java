package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.structure.SteveGravePlacer;
import com.orangevillager61.emeraldcapitalism.world.structure.SteveGraveSavedData;
import com.orangevillager61.emeraldcapitalism.world.structure.SteveGraveTargetPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.Optional;

/** Server lifecycle for the single deterministic Steve grave. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class SteveGraveEvents {
    private static final int MAX_PLACEMENTS_PER_TICK = 1;

    private static ServerLevel queuedLevel;
    private static boolean placementQueued;

    private SteveGraveEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD) {
            return;
        }

        SteveGraveSavedData data = SteveGraveSavedData.get(level);
        BlockPos target = data.target();
        if (target != null && !data.isPlaced()
                && new ChunkPos(target).equals(event.getChunk().getPos())) {
            queuePlacement(level);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        SteveGraveSavedData data = SteveGraveSavedData.get(level);

        if (data.placementState() == SteveGraveSavedData.PlacementState.UNRESOLVED) {
            resolveTarget(level, data);
        }

        if (data.placementState() == SteveGraveSavedData.PlacementState.TARGET_FOUND
                && data.target() != null) {
            ChunkPos targetChunk = new ChunkPos(data.target());
            if (level.getChunkSource().hasChunk(targetChunk.x, targetChunk.z)) {
                queuePlacement(level);
            }
        }

        if (MAX_PLACEMENTS_PER_TICK > 0 && placementQueued && queuedLevel != null) {
            placementQueued = false;
            tryPlace(queuedLevel);
        }
    }

    /** Clears the transient queue; SavedData remains the durable source of truth. */
    public static void clearPendingWork() {
        queuedLevel = null;
        placementQueued = false;
    }

    private static void resolveTarget(ServerLevel level, SteveGraveSavedData data) {
        BlockPos spawn = level.getSharedSpawnPos();
        data.setSpawnAnchor(spawn);

        Optional<StructureTemplate> template = level.getStructureManager().get(SteveGravePlacer.TEMPLATE_ID);
        if (template.isEmpty()) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Cannot resolve Steve grave: missing template {}",
                    SteveGravePlacer.TEMPLATE_ID);
            data.markSearchFailed();
            return;
        }

        Optional<BlockPos> target = SteveGraveTargetPlanner.findTarget(level, spawn, template.get());
        if (target.isPresent()) {
            data.setTarget(target.get());
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Steve grave target resolved at X={}, Z={} relative to initial spawn ({}, {})",
                    target.get().getX(), target.get().getZ(), spawn.getX(), spawn.getZ());
        } else {
            data.markSearchFailed();
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Could not find a deterministic Frozen Peaks location for Steve grave");
        }
    }

    private static void queuePlacement(ServerLevel level) {
        queuedLevel = level;
        placementQueued = true;
    }

    private static void tryPlace(ServerLevel level) {
        SteveGraveSavedData data = SteveGraveSavedData.get(level);
        BlockPos target = data.target();
        if (target == null || data.isPlaced()) {
            return;
        }

        SteveGravePlacer.PlacementAttempt attempt = SteveGravePlacer.placeIfReady(level, target);
        if (attempt.status() == SteveGravePlacer.AttemptStatus.PLACED) {
            data.markPlaced(attempt.origin());
        } else if (attempt.status() == SteveGravePlacer.AttemptStatus.FAILED) {
            data.markPlacementFailed();
        }
    }
}
