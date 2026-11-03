package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.behavior.VillagerBehaviorRegistry;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.command.StateReloadScenarioCommand;
import com.orangevillager61.emeraldcapitalism.event.VillageRegistryEvents;
import com.orangevillager61.emeraldcapitalism.event.VillagerSpawnEvents;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The single owner of transient villager execution state at lifecycle boundaries.
 * Durable task progress stays with its record; everything released here is rebuilt
 * from that progress when the villager next acts.
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class VillagerSessionCleanup {

    private VillagerSessionCleanup() {
    }

    /** Covers removal, unload, and dimension change; the old entity instance ends here. */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Villager villager
                && event.getLevel() instanceof ServerLevel level) {
            releaseEntity(level, villager, villager.getRemovalReason());
        }
    }

    /** Runs after death handlers so a cancelled death keeps the villager's session. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!event.isCanceled() && event.getEntity() instanceof Villager villager
                && villager.level() instanceof ServerLevel level) {
            releaseWork(level, villager.getUUID());
            releaseBreedingAndBegging(level, villager);
            LumberjackSaplingCache.clearOwner(level, villager.getUUID());
        }
    }

    /**
     * Ends only the old profession's work. A ladder traversal and the villager's
     * social sessions are not profession work and continue.
     */
    public static void onProfessionChanged(Villager villager) {
        if (!villager.isAddedToLevel() || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        releaseWork(level, villager.getUUID());
        LumberjackSaplingCache.clearOwner(level, villager.getUUID());
        MovementRequestHandle handle = MovementRequestManager.activeHandle(villager);
        if (handle != null) {
            MovementRequestManager.cancel(villager, handle, MovementRequestTerminationReason.ABANDONED);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            VillageRegistryEvents.releaseLevelState(level);
            VillagerBreedingSessions.clearLevel(level);
            LumberjackSaplingCache.clear(level);
            LumberjackTreeReservations.clear(level);
            VillagerLadderClaims.releaseDimension(level.dimension());
        }
    }

    /** Ends every session; {@code reason} is null at server start, where nothing was terminal. */
    public static void releaseAll(@Nullable MovementRequestTerminationReason reason) {
        VillageRegistryEvents.clearManagers();
        VillagerBreedingSessions.clearAll();
        LumberjackTreeReservations.clearAll();
        LumberjackSaplingCache.clearAll();
        VillagerLadderClaims.clear();
        MovementRequestManager.clearAll(reason);
        // Manager shutdown accounts for active requests once. Lifecycle hooks
        // run afterward and their now-stale handles cannot add ABANDONED.
        VillagerBehaviorRegistry.clearBindings();
        StateReloadScenarioCommand.clearWatch();
        VillagerSpawnEvents.clearParentAssignmentRetries();
    }

    private static void releaseEntity(ServerLevel level, Villager villager,
                                      @Nullable Entity.RemovalReason reason) {
        releaseWork(level, villager.getUUID());
        releaseBreedingAndBegging(level, villager);
        VillagerLadderClaims.releaseAll(villager);
        VillagerBehaviorRegistry.release(villager);
        MovementRequestManager.release(villager);
        VillagerSpawnEvents.releaseParentAssignmentRetries(villager.getUUID());
        // An unloaded lumberjack still owns its saplings when its chunk returns.
        if (reason == null || !reason.shouldSave()) {
            LumberjackSaplingCache.clearOwner(level, villager.getUUID());
        }
    }

    private static void releaseWork(ServerLevel level, UUID villagerId) {
        VillageRegistryManager manager = VillageRegistryEvents.findManager(level);
        if (manager != null) {
            manager.releaseVillagerWork(villagerId);
        }
        LumberjackTreeReservations.releaseOwner(level, villagerId);
        BankBlockEntity.releaseGolemConstructionOwner(level, villagerId);
    }

    private static void releaseBreedingAndBegging(ServerLevel level, Villager villager) {
        VillagerBreedingSessions.abort(level, villager,
                VillagerBreedingSessions.AbortReason.ABORTED_TARGET_INVALID, true);
        villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS).resetBeggingState();
    }
}
