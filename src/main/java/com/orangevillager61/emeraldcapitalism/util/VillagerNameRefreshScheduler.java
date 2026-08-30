package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Server-owned dirty queue for derived villager names.
 *
 * <p>Stable name inputs are never polled from every villager tick. Mutation
 * points enqueue a UUID, village renames fan out through an index of loaded
 * origin villagers, and baby/adult transitions have a deadline as a safety net
 * in addition to the exact vanilla boundary hook.</p>
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class VillagerNameRefreshScheduler {

    private static final int MAX_REFRESHES_PER_LEVEL_TICK = 16;
    private static final Map<ServerLevel, LevelState> LEVEL_STATES = new IdentityHashMap<>();

    private VillagerNameRefreshScheduler() {
    }

    /** Enqueues a rebuild after an input such as profession or age changes. */
    public static void requestRefresh(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level) || villager.isRemoved()) {
            return;
        }
        LevelState state = state(level);
        state.track(villager);
        state.enqueue(villager.getUUID());
    }

    /** Updates the loaded-origin index after a name is assigned directly. */
    public static void trackVillager(Villager villager) {
        if (villager.level() instanceof ServerLevel level && !villager.isRemoved()) {
            state(level).track(villager);
        }
    }

    /** Enqueues only loaded villagers whose persisted origin is this village. */
    public static void requestVillageRefresh(ServerLevel level, UUID villageId) {
        LevelState state = state(level);
        Set<UUID> villagers = state.loadedByOriginVillage.get(villageId);
        if (villagers != null) {
            state.enqueueAll(villagers);
        }
    }

    /** Rebuilds loaded derived names after the server naming resource is reloaded. */
    public static void requestAllLoadedRefresh() {
        List<ServerLevel> levels;
        synchronized (LEVEL_STATES) {
            levels = new ArrayList<>(LEVEL_STATES.keySet());
        }
        for (ServerLevel level : levels) {
            level.getServer().execute(() -> {
                LevelState state = existingState(level);
                if (state != null) {
                    state.enqueueAll(state.loadedVillagers);
                }
            });
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Villager villager && event.getLevel() instanceof ServerLevel) {
            // Run once on the next level tick. Other join handlers may assign
            // the persistent personal slot later in this same event dispatch.
            requestRefresh(villager);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Villager villager
                && event.getLevel() instanceof ServerLevel level) {
            LevelState state = existingState(level);
            if (state != null) {
                state.untrack(villager.getUUID());
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelState state = existingState(level);
            if (state != null) {
                state.tick(level);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (LEVEL_STATES) {
                LEVEL_STATES.remove(level);
            }
        }
    }

    private static LevelState state(ServerLevel level) {
        synchronized (LEVEL_STATES) {
            return LEVEL_STATES.computeIfAbsent(level, ignored -> new LevelState());
        }
    }

    private static LevelState existingState(ServerLevel level) {
        synchronized (LEVEL_STATES) {
            return LEVEL_STATES.get(level);
        }
    }

    private record AdultDeadline(UUID villagerId, long gameTime) {
    }

    private static final class LevelState {
        private final ArrayDeque<UUID> dirtyQueue = new ArrayDeque<>();
        private final Set<UUID> dirtyVillagers = new HashSet<>();
        private final Set<UUID> loadedVillagers = new HashSet<>();
        private final Map<UUID, UUID> originVillageByVillager = new HashMap<>();
        private final Map<UUID, Set<UUID>> loadedByOriginVillage = new HashMap<>();
        private final Map<UUID, Long> adulthoodDeadlineByVillager = new HashMap<>();
        private final PriorityQueue<AdultDeadline> adulthoodDeadlines = new PriorityQueue<>(
                Comparator.comparingLong(AdultDeadline::gameTime));

        private void enqueue(UUID villagerId) {
            if (dirtyVillagers.add(villagerId)) {
                dirtyQueue.addLast(villagerId);
            }
        }

        private void enqueueAll(Iterable<UUID> villagerIds) {
            for (UUID villagerId : villagerIds) {
                enqueue(villagerId);
            }
        }

        private void track(Villager villager) {
            UUID villagerId = villager.getUUID();
            loadedVillagers.add(villagerId);

            VillagerStatsAttachment stats = villager.getData(
                    EmeraldCapitalismAttachments.VILLAGER_STATS);
            UUID newOrigin = stats.getNamingVillageId();
            UUID oldOrigin = originVillageByVillager.get(villagerId);
            if (!java.util.Objects.equals(oldOrigin, newOrigin)) {
                removeFromOriginIndex(villagerId, oldOrigin);
                if (newOrigin == null) {
                    originVillageByVillager.remove(villagerId);
                } else {
                    originVillageByVillager.put(villagerId, newOrigin);
                    loadedByOriginVillage.computeIfAbsent(newOrigin, ignored -> new HashSet<>())
                            .add(villagerId);
                }
            }

            updateAdulthoodDeadline(villager);
        }

        private void updateAdulthoodDeadline(Villager villager) {
            UUID villagerId = villager.getUUID();
            if (!villager.isBaby()) {
                adulthoodDeadlineByVillager.remove(villagerId);
                return;
            }

            long remainingTicks = Math.max(1L, -(long) villager.getAge());
            long deadline = villager.level().getGameTime() + remainingTicks;
            Long previous = adulthoodDeadlineByVillager.put(villagerId, deadline);
            if (previous == null || previous.longValue() != deadline) {
                adulthoodDeadlines.add(new AdultDeadline(villagerId, deadline));
            }
        }

        private void untrack(UUID villagerId) {
            loadedVillagers.remove(villagerId);
            dirtyVillagers.remove(villagerId);
            adulthoodDeadlineByVillager.remove(villagerId);
            removeFromOriginIndex(villagerId, originVillageByVillager.remove(villagerId));
        }

        private void removeFromOriginIndex(UUID villagerId, UUID originVillageId) {
            if (originVillageId == null) {
                return;
            }
            Set<UUID> villagers = loadedByOriginVillage.get(originVillageId);
            if (villagers != null) {
                villagers.remove(villagerId);
                if (villagers.isEmpty()) {
                    loadedByOriginVillage.remove(originVillageId);
                }
            }
        }

        private void tick(ServerLevel level) {
            processAdultDeadlines(level);

            int processed = 0;
            while (processed < MAX_REFRESHES_PER_LEVEL_TICK && !dirtyQueue.isEmpty()) {
                UUID villagerId = dirtyQueue.removeFirst();
                if (!dirtyVillagers.remove(villagerId)) {
                    continue;
                }
                Entity entity = level.getEntity(villagerId);
                if (entity instanceof Villager villager && !villager.isRemoved()) {
                    VillagerNameManager.refreshNameIfNeeded(villager);
                    track(villager);
                } else {
                    untrack(villagerId);
                }
                processed++;
            }
        }

        private void processAdultDeadlines(ServerLevel level) {
            long gameTime = level.getGameTime();
            while (!adulthoodDeadlines.isEmpty() && adulthoodDeadlines.peek().gameTime() <= gameTime) {
                AdultDeadline deadline = adulthoodDeadlines.remove();
                Long current = adulthoodDeadlineByVillager.get(deadline.villagerId());
                if (current == null || current.longValue() != deadline.gameTime()) {
                    continue;
                }
                adulthoodDeadlineByVillager.remove(deadline.villagerId());
                Entity entity = level.getEntity(deadline.villagerId());
                if (entity instanceof Villager villager && !villager.isRemoved()) {
                    if (villager.isBaby()) {
                        updateAdulthoodDeadline(villager);
                    } else {
                        enqueue(deadline.villagerId());
                    }
                } else {
                    untrack(deadline.villagerId());
                }
            }
        }
    }
}
