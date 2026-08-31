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

    private static final int WORK_POSITION_EXCLUSION_RADIUS = 2;
    private static final Map<ServerLevel, ReservationState> RESERVATIONS = new IdentityHashMap<>();

    private LumberjackTreeReservations() {
    }

    static boolean tryReserve(ServerLevel level, UUID owner, Collection<BlockPos> logs) {
        return tryReserve(level, owner, logs, null);
    }

    /** Atomically reserves the tree logs and the standing position used to work them. */
    static boolean tryReserve(ServerLevel level, UUID owner, Collection<BlockPos> logs,
                              BlockPos workPosition) {
        ReservationState state = reservationsFor(level);
        pruneMissingOwners(level, state);
        for (BlockPos log : logs) {
            UUID reservedBy = state.logs.get(log);
            if (reservedBy != null && !reservedBy.equals(owner)) {
                return false;
            }
        }
        if (workPosition != null && isWorkPositionReservedByOther(state, owner, workPosition)) {
            return false;
        }

        for (BlockPos log : logs) {
            state.logs.put(log.immutable(), owner);
        }
        if (workPosition != null) {
            state.workPositions.put(workPosition.immutable(), owner);
        }
        return true;
    }

    static boolean isReservedByOther(ServerLevel level, UUID owner, Collection<BlockPos> logs) {
        prune(level);
        return isLogReservedByOther(level, owner, logs);
    }

    /** Fast log-only check for a scan that already pruned stale reservations. */
    static boolean isLogReservedByOther(ServerLevel level, UUID owner, Collection<BlockPos> logs) {
        ReservationState state = RESERVATIONS.get(level);
        if (state == null) {
            return false;
        }
        for (BlockPos log : logs) {
            if (isLogReservedByOther(state, owner, log)) {
                return true;
            }
        }
        return false;
    }

    static boolean isLogReservedByOther(ServerLevel level, UUID owner, BlockPos log) {
        ReservationState state = RESERVATIONS.get(level);
        return state != null && isLogReservedByOther(state, owner, log);
    }

    static boolean isWorkPositionReservedByOther(ServerLevel level, UUID owner, BlockPos workPosition) {
        prune(level);
        ReservationState state = RESERVATIONS.get(level);
        return state != null && isWorkPositionReservedByOther(state, owner, workPosition);
    }

    static void release(ServerLevel level, UUID owner, Collection<BlockPos> logs) {
        release(level, owner, logs, null);
    }

    static void release(ServerLevel level, UUID owner, Collection<BlockPos> logs,
                        BlockPos workPosition) {
        ReservationState state = RESERVATIONS.get(level);
        if (state == null) {
            return;
        }
        for (BlockPos log : logs) {
            if (owner.equals(state.logs.get(log))) {
                state.logs.remove(log);
            }
        }
        if (workPosition != null && owner.equals(state.workPositions.get(workPosition))) {
            state.workPositions.remove(workPosition);
        }
        if (state.isEmpty()) {
            RESERVATIONS.remove(level);
        }
    }

    /** Prunes stale owners once before a wide scan rather than once per candidate. */
    static void prune(ServerLevel level) {
        ReservationState state = RESERVATIONS.get(level);
        if (state == null) {
            return;
        }
        pruneMissingOwners(level, state);
        if (state.isEmpty()) {
            RESERVATIONS.remove(level);
        }
    }

    public static void clearAll() {
        RESERVATIONS.clear();
    }

    public static void clear(ServerLevel level) {
        RESERVATIONS.remove(level);
    }

    private static ReservationState reservationsFor(ServerLevel level) {
        return RESERVATIONS.computeIfAbsent(level, ignored -> new ReservationState());
    }

    private static boolean isWorkPositionReservedByOther(ReservationState state, UUID owner,
                                                           BlockPos workPosition) {
        int radius = WORK_POSITION_EXCLUSION_RADIUS;
        for (Map.Entry<BlockPos, UUID> entry : state.workPositions.entrySet()) {
            if (!owner.equals(entry.getValue())
                    && entry.getKey().distSqr(workPosition) <= (double) radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLogReservedByOther(ReservationState state, UUID owner, BlockPos log) {
        UUID reservedBy = state.logs.get(log);
        return reservedBy != null && !reservedBy.equals(owner);
    }

    private static void pruneMissingOwners(ServerLevel level, ReservationState state) {
        Iterator<Map.Entry<BlockPos, UUID>> iterator = state.logs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, UUID> entry = iterator.next();
            if (level.getEntity(entry.getValue()) == null) {
                iterator.remove();
            }
        }
        iterator = state.workPositions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, UUID> entry = iterator.next();
            if (level.getEntity(entry.getValue()) == null) {
                iterator.remove();
            }
        }
    }

    private static final class ReservationState {
        private final Map<BlockPos, UUID> logs = new HashMap<>();
        private final Map<BlockPos, UUID> workPositions = new HashMap<>();

        private boolean isEmpty() {
            return logs.isEmpty() && workPositions.isEmpty();
        }
    }
}
