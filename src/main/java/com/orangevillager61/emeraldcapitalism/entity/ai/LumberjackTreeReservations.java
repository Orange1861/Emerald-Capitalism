package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Holds transient server-side reservations for trees selected by lumberjacks.
 * Reservations are intentionally not persisted: they describe live goal state
 * and are rebuilt when a lumberjack selects work after a world restart.
 */
public final class LumberjackTreeReservations {

    private static final Map<ServerLevel, Map<BlockPos, UUID>> RESERVATIONS = new IdentityHashMap<>();

    private LumberjackTreeReservations() {
    }

    static boolean tryReserve(ServerLevel level, UUID owner, Collection<BlockPos> logs) {
        Map<BlockPos, UUID> levelReservations = reservationsFor(level);
        pruneMissingOwners(level, levelReservations);
        for (BlockPos log : logs) {
            UUID reservedBy = levelReservations.get(log);
            if (reservedBy != null && !reservedBy.equals(owner)) {
                return false;
            }
        }

        for (BlockPos log : logs) {
            levelReservations.put(log.immutable(), owner);
        }
        return true;
    }

    static boolean isReservedByOther(ServerLevel level, UUID owner, Collection<BlockPos> logs) {
        Map<BlockPos, UUID> levelReservations = RESERVATIONS.get(level);
        if (levelReservations == null) {
            return false;
        }
        pruneMissingOwners(level, levelReservations);
        for (BlockPos log : logs) {
            UUID reservedBy = levelReservations.get(log);
            if (reservedBy != null && !reservedBy.equals(owner)) {
                return true;
            }
        }
        return false;
    }

    static void release(ServerLevel level, UUID owner, Collection<BlockPos> logs) {
        Map<BlockPos, UUID> levelReservations = RESERVATIONS.get(level);
        if (levelReservations == null) {
            return;
        }
        for (BlockPos log : logs) {
            if (owner.equals(levelReservations.get(log))) {
                levelReservations.remove(log);
            }
        }
        if (levelReservations.isEmpty()) {
            RESERVATIONS.remove(level);
        }
    }

    public static void clearAll() {
        RESERVATIONS.clear();
    }

    private static Map<BlockPos, UUID> reservationsFor(ServerLevel level) {
        return RESERVATIONS.computeIfAbsent(level, ignored -> new HashMap<>());
    }

    private static void pruneMissingOwners(ServerLevel level, Map<BlockPos, UUID> levelReservations) {
        Iterator<Map.Entry<BlockPos, UUID>> iterator = levelReservations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, UUID> entry = iterator.next();
            if (level.getEntity(entry.getValue()) == null) {
                iterator.remove();
            }
        }
    }
}
