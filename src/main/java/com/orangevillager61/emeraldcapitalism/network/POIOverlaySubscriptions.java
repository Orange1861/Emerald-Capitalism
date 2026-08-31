package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side tracking of which players are subscribed to POI overlay updates.
 * When subscribed, a fresh {@link VillagePOIDataPacket} is pushed every
 * {@link #PUSH_INTERVAL_TICKS} ticks.
 */
@SuppressWarnings("resource")
public final class POIOverlaySubscriptions {

    /** Interval between automatic POI data pushes (80 ticks ≈ 4 seconds). */
    private static final int PUSH_INTERVAL_TICKS = 80;

    /** Player UUID → subscribed Village UUID. */
    private static final Map<UUID, UUID> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    private POIOverlaySubscriptions() {}

    public static void subscribe(UUID playerId, UUID villageId) {
        SUBSCRIPTIONS.put(playerId, villageId);
    }

    /** Changes the village for an existing subscription without enabling it. */
    static void retargetIfSubscribed(UUID playerId, UUID villageId) {
        SUBSCRIPTIONS.replace(playerId, villageId);
    }

    /** Package-private test/support boundary for asserting the server-side target. */
    static boolean isSubscribedTo(UUID playerId, UUID villageId) {
        return villageId.equals(SUBSCRIPTIONS.get(playerId));
    }

    public static void unsubscribe(UUID playerId) {
        SUBSCRIPTIONS.remove(playerId);
    }

    public static boolean isSubscribed(UUID playerId) {
        return SUBSCRIPTIONS.containsKey(playerId);
    }

    /** Called when a player disconnects to clean up. */
    public static void onPlayerDisconnect(UUID playerId) {
        SUBSCRIPTIONS.remove(playerId);
    }

    /** Clears all subscriptions at server lifecycle boundaries. */
    public static void clearAll() {
        SUBSCRIPTIONS.clear();
    }

    /**
     * Called every server tick. Pushes data to subscribed players on the interval.
     */
    public static void tick(ServerLevel level, long tickCount) {
        if (tickCount % PUSH_INTERVAL_TICKS != 0) {
            return;
        }

        VillagePOIDataCache.evictExpired(level.dimension(), level.getGameTime(), 256);

        if (SUBSCRIPTIONS.isEmpty()) {
            return;
        }

        VillageRegistryData data = VillageRegistryData.get(level);
        List<UUID> toRemove = new ArrayList<>();
        Map<UUID, List<ServerPlayer>> subscribersByVillage = new HashMap<>();

        for (Map.Entry<UUID, UUID> entry : SUBSCRIPTIONS.entrySet()) {
            UUID playerId = entry.getKey();
            UUID villageId = entry.getValue();

            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player == null || PacketHandlerUtil.serverLevel(player) != level) {
                continue; // Player not in this level, skip (don't remove, they might return)
            }

            VillageRecord village = data.getVillages().get(villageId);
            if (village == null) {
                toRemove.add(playerId);
                continue;
            }

            if (!VillagePOIAccessPolicy.isLocalContextValid(player, level, village)) {
                toRemove.add(playerId);
                continue;
            }

            subscribersByVillage.computeIfAbsent(villageId, ignored -> new ArrayList<>()).add(player);
        }

        for (Map.Entry<UUID, List<ServerPlayer>> entry : subscribersByVillage.entrySet()) {
            VillageRecord village = data.getVillages().get(entry.getKey());
            if (village == null) {
                continue;
            }

            // Verify each village once, regardless of how many players subscribe to it.
            if (village.verify(level)) {
                data.setDirty();
            }

            VillagePOIDataFactory.SharedSnapshot sharedSnapshot =
                    VillagePOIDataFactory.snapshotSharedState(village, level);

            for (ServerPlayer player : entry.getValue()) {
                boolean isOp = player.hasPermissions(com.orangevillager61.emeraldcapitalism.Config.villageCommandPermissionLevel);
                // Reputation and village opinion are viewer-specific, so each
                // subscriber receives a packet built for that player.
                VillagePOIDataPacket packet = VillagePOIDataCache.getOrBuild(
                        level, village, isOp, player, sharedSnapshot);
                PacketDistributor.sendToPlayer(player, packet);
            }
        }

        for (UUID id : toRemove) {
            SUBSCRIPTIONS.remove(id);
        }
    }
}
