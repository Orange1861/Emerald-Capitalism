package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Shared access-policy checks for server-side village POI payloads. */
public final class VillagePOIAccessPolicy {

    private VillagePOIAccessPolicy() {}

    /**
     * A player is local to a village when they are in the same dimension and
     * inside the village bounds.
     *
     * Operators with the configured village command permission level may bypass
     * the physical village bounding-box check so they can inspect nearby
     * villages while standing outside the current bounds.
     */
    public static boolean isLocalContextValid(ServerPlayer player, ServerLevel level, VillageRecord village) {
        if (PacketHandlerUtil.serverLevel(player) != level) {
            return false;
        }

        if (player.hasPermissions(Config.villageCommandPermissionLevel)) {
            return true;
        }

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        return village.getBoundingBox().contains(x, y, z);
    }

    /**
     * Validates a player context for a server-side village mutation.
     * Spectators may inspect a manager menu, but cannot change village state.
     */
    public static boolean isMutationContextValid(ServerPlayer player, ServerLevel level,
                                                  VillageRecord village) {
        return !player.isSpectator() && isLocalContextValid(player, level, village);
    }
}
