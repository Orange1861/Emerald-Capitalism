package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shares one live village-opinion calculation between systems during a server tick.
 * The cache is deliberately tick-scoped because villager reputations are live state.
 */
public final class VillageOpinionCache {

    private static final Map<ResourceKey<Level>, TickCache> CACHES = new HashMap<>();

    private VillageOpinionCache() {
    }

    public static int get(ServerLevel level, VillageRecord village, Player player) {
        ResourceKey<Level> dimension = level.dimension();
        TickCache cache = CACHES.computeIfAbsent(dimension, ignored -> new TickCache());
        long gameTime = level.getGameTime();
        if (cache.tick != gameTime) {
            cache.tick = gameTime;
            cache.values.clear();
        }

        OpinionKey key = new OpinionKey(village.getVillageId(), player.getUUID());
        return cache.values.computeIfAbsent(key, ignored -> village.calculateVillageOpinion(level, player));
    }

    /** Drops entries affected by a same-tick persistent opinion update. */
    public static void invalidateVillage(UUID villageId) {
        for (TickCache cache : CACHES.values()) {
            cache.values.keySet().removeIf(key -> key.villageId().equals(villageId));
        }
    }

    /** Clears all live references at a server lifecycle boundary. */
    public static void clearAll() {
        CACHES.clear();
    }

    private static final class TickCache {
        private long tick = Long.MIN_VALUE;
        private final Map<OpinionKey, Integer> values = new HashMap<>();
    }

    private record OpinionKey(UUID villageId, UUID playerId) {
    }
}
