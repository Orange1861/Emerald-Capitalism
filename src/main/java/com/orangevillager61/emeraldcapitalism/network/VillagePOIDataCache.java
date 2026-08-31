package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Small server-side TTL cache for village POI payloads. */
public final class VillagePOIDataCache {

    private static final long TTL_TICKS = 20L;

    private record CacheKey(ResourceKey<Level> dimension, UUID villageId, boolean isOp, UUID viewerId) {}
    private record CachedEntry(VillagePOIDataPacket packet, long expiresAtTick) {}

    private static final Map<CacheKey, CachedEntry> CACHE = new ConcurrentHashMap<>();

    private VillagePOIDataCache() {}

    public static VillagePOIDataPacket getOrBuild(ServerLevel level, VillageRecord village, boolean isOp) {
        return getOrBuild(level, village, isOp, null);
    }

    /** Returns a short-lived snapshot cached separately for each viewer's opinion values. */
    public static VillagePOIDataPacket getOrBuild(ServerLevel level, VillageRecord village,
                                                  boolean isOp, ServerPlayer viewer) {
        return getOrBuild(level, village, isOp, viewer, null);
    }

    /** Builds a packet using shared live values computed once for an overlay village group. */
    static VillagePOIDataPacket getOrBuild(ServerLevel level, VillageRecord village,
                                           boolean isOp, ServerPlayer viewer,
                                           VillagePOIDataFactory.SharedSnapshot sharedSnapshot) {
        long now = level.getGameTime();
        CacheKey key = new CacheKey(level.dimension(), village.getVillageId(), isOp,
                viewer == null ? null : viewer.getUUID());
        CachedEntry cached = CACHE.get(key);
        if (cached != null && cached.expiresAtTick() >= now) {
            return cached.packet();
        }

        VillagePOIDataPacket packet = VillagePOIDataFactory.build(
                village, level, isOp, viewer, sharedSnapshot);
        CACHE.put(key, new CachedEntry(packet, now + TTL_TICKS));
        return packet;
    }

    public static VillagePOIDataPacket getIfPresent(ServerLevel level, VillageRecord village, boolean isOp) {
        return getIfPresent(level, village, isOp, null);
    }

    public static VillagePOIDataPacket getIfPresent(ServerLevel level, VillageRecord village,
                                                    boolean isOp, ServerPlayer viewer) {
        long now = level.getGameTime();
        CacheKey key = new CacheKey(level.dimension(), village.getVillageId(), isOp,
                viewer == null ? null : viewer.getUUID());
        CachedEntry cached = CACHE.get(key);
        if (cached != null && cached.expiresAtTick() >= now) {
            return cached.packet();
        }
        return null;
    }

    public static void invalidateVillage(UUID villageId) {
        CACHE.keySet().removeIf(k -> k.villageId().equals(villageId));
    }

    public static void invalidateViewer(UUID viewerId) {
        CACHE.keySet().removeIf(k -> viewerId.equals(k.viewerId()));
    }

    public static void invalidateDimension(ResourceKey<Level> dimension) {
        CACHE.keySet().removeIf(k -> dimension.equals(k.dimension()));
    }

    /** Bounded cleanup for entries that have expired without being read again. */
    public static int evictExpired(ResourceKey<Level> dimension, long now, int budget) {
        if (budget <= 0) {
            return 0;
        }
        int removed = 0;
        Iterator<Map.Entry<CacheKey, CachedEntry>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext() && removed < budget) {
            Map.Entry<CacheKey, CachedEntry> entry = iterator.next();
            CachedEntry cached = entry.getValue();
            if (dimension.equals(entry.getKey().dimension()) && cached.expiresAtTick() < now
                    && CACHE.remove(entry.getKey(), cached)) {
                removed++;
            }
        }
        return removed;
    }

    public static void clearAll() {
        CACHE.clear();
    }
}
