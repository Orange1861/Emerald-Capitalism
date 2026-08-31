package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.pathfinder.Path;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Server-authoritative breeding sessions. A session owns one reserved Home POI
 * until the child is committed or the attempt is aborted.
 */
public final class VillagerBreedingSessions {

    public static final int MINIMUM_HUNGER = 12;
    private static final long COURTSHIP_TIMEOUT_TICKS = 350L;
    private static final long PAIR_RETRY_COOLDOWN_TICKS = 100L;

    private static final Map<ServerLevel, Map<UUID, Session>> ACTIVE = new IdentityHashMap<>();
    private static final Map<ServerLevel, Map<PairKey, Long>> BLOCKED_PAIRS = new IdentityHashMap<>();
    private static final Map<ServerLevel, PriorityQueue<SessionDeadline>> DEADLINES = new IdentityHashMap<>();

    private VillagerBreedingSessions() {
    }

    public enum StartResult {
        STARTED,
        ALREADY_ACTIVE,
        BLOCKED_BUSY,
        BLOCKED_RETRY,
        BLOCKED_NO_BED
    }

    public enum AbortReason {
        ABORTED_TARGET_INVALID,
        ABORTED_PANIC,
        ABORTED_TIMEOUT,
        BLOCKED_NO_BED,
        BLOCKED_HUNGER,
        BLOCKED_RELATED,
        SPAWN_REJECTED
    }

    /** Attempts to reserve a reachable vacant bed and begin a courtship session. */
    public static StartResult tryStart(ServerLevel level, Villager owner, Villager partner) {
        long gameTime = level.getGameTime();
        cleanupExpiredPairCooldowns(level, gameTime);

        UUID ownerId = owner.getUUID();
        UUID partnerId = partner.getUUID();
        Session ownerSession = sessions(level).get(ownerId);
        if (ownerSession != null) {
            return ownerSession.matches(ownerId, partnerId) ? StartResult.ALREADY_ACTIVE : StartResult.BLOCKED_BUSY;
        }
        Session partnerSession = sessions(level).get(partnerId);
        if (partnerSession != null) {
            return StartResult.BLOCKED_BUSY;
        }

        PairKey pair = PairKey.of(ownerId, partnerId);
        Long retryUntil = blockedPairs(level).get(pair);
        if (retryUntil != null && retryUntil > gameTime) {
            return StartResult.BLOCKED_RETRY;
        }
        blockedPairs(level).remove(pair);

        Optional<BlockPos> reservedBed = reserveBed(level, owner);
        if (reservedBed.isEmpty()) {
            blockPair(level, owner, partner, gameTime);
            return StartResult.BLOCKED_NO_BED;
        }

        Session session = new Session(ownerId, partnerId, reservedBed.get().immutable(),
                gameTime + COURTSHIP_TIMEOUT_TICKS, pair);
        sessions(level).put(ownerId, session);
        sessions(level).put(partnerId, session);
        deadlines(level).add(new SessionDeadline(session.expiresAt() + 1L, session));
        return StartResult.STARTED;
    }

    /** Returns true when a villager owns an active courtship session. */
    public static boolean isActive(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        Map<UUID, Session> activeSessions = ACTIVE.get(level);
        return activeSessions != null && activeSessions.containsKey(villager.getUUID());
    }

    /** Returns true while vanilla has assigned this villager a breeding target. */
    public static boolean hasBreedingTarget(Villager villager) {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET);
    }

    /** Custom movement work must yield as soon as courtship is selected. */
    public static boolean shouldYieldCustomWork(Villager villager) {
        return isActive(villager) || hasBreedingTarget(villager);
    }

    public static boolean isActivePair(ServerLevel level, Villager first, Villager second) {
        Map<UUID, Session> activeSessions = ACTIVE.get(level);
        Session session = activeSessions == null ? null : activeSessions.get(first.getUUID());
        return session != null && session.matches(first.getUUID(), second.getUUID());
    }

    /** Returns true when this exact pair is currently blocked by a retry cooldown. */
    public static boolean isPairBlocked(ServerLevel level, Villager first, Villager second) {
        Map<PairKey, Long> blocked = BLOCKED_PAIRS.get(level);
        Long retryUntil = blocked == null ? null : blocked.get(PairKey.of(first.getUUID(), second.getUUID()));
        return retryUntil != null && retryUntil > level.getGameTime();
    }

    /** Blocks a rejected pair briefly and clears their stale breeding memories. */
    public static void rejectPair(ServerLevel level, Villager first, Villager second, AbortReason reason) {
        blockPair(level, first, second, level.getGameTime());
    }

    /**
     * Returns the session's bed for the vanilla birth path. If the physical bed
     * disappeared, the reservation is aborted before vanilla can attempt a birth.
     */
    public static Optional<BlockPos> getReservedBed(ServerLevel level, Villager parent) {
        Session session = sessions(level).get(parent.getUUID());
        if (session == null) {
            return Optional.empty();
        }
        boolean stillHomePoi = level.getPoiManager().getType(session.bed())
                .filter(type -> type.is(PoiTypes.HOME))
                .isPresent();
        if (!stillHomePoi || !isUsableBed(level, session.bed())) {
            abort(level, parent, AbortReason.BLOCKED_NO_BED, true);
            return Optional.empty();
        }
        return Optional.of(session.bed());
    }

    /** Commits the session after vanilla successfully adds the newborn. */
    public static void commitBirth(ServerLevel level, Villager parent, Villager partner) {
        Session session = removeSession(level, parent.getUUID(), partner.getUUID());
        if (session == null || session.committed()) {
            return;
        }
        session.markCommitted();
    }

    /**
     * Handles NeoForge/other-mod spawn rejection. The manager releases the
     * reservation immediately; vanilla's fallback release is harmless because
     * the POI has already returned to its maximum free-ticket count.
     */
    public static void handleSpawnRejected(ServerLevel level, Villager parent, Villager partner) {
        Session session = removeSession(level, parent.getUUID(), partner.getUUID());
        if (session == null) {
            return;
        }
        releaseBed(level, session.bed());
        if (parent.getAge() > 0) parent.setAge(0);
        if (partner.getAge() > 0) partner.setAge(0);
        blockPair(level, parent, partner, level.getGameTime());
        clearBreedingMemories(parent, partner);
    }

    /** Aborts an active session and releases its reserved POI ticket. */
    public static void abort(ServerLevel level, Villager villager, AbortReason reason, boolean releaseBed) {
        Session session = sessions(level).get(villager.getUUID());
        if (session == null) {
            return;
        }

        removeSession(level, session);
        if (releaseBed) {
            releaseBed(level, session.bed());
        }

        Villager partner = findVillager(level, session.other(villager.getUUID()));
        clearBreedingMemories(villager, partner);
        blockPair(level, villager, partner, level.getGameTime());
    }

    /** Called by a behavior stop hook to classify and clean up an interrupted session. */
    public static void abortFromStop(ServerLevel level, Villager villager) {
        Session session = sessions(level).get(villager.getUUID());
        if (session == null) {
            return;
        }
        AbortReason reason;
        if (level.getGameTime() > session.expiresAt()) {
            reason = AbortReason.ABORTED_TIMEOUT;
        } else if (villager.getBrain().getActiveNonCoreActivity().filter(Activity.PANIC::equals).isPresent()) {
            reason = AbortReason.ABORTED_PANIC;
        } else {
            reason = AbortReason.ABORTED_TARGET_INVALID;
        }
        abort(level, villager, reason, true);
    }

    /** Processes only courtship sessions whose absolute timeout deadline is due. */
    public static void tick(ServerLevel level) {
        long gameTime = level.getGameTime();
        PriorityQueue<SessionDeadline> deadlines = DEADLINES.get(level);
        if (deadlines == null) {
            return;
        }

        Map<UUID, Session> activeSessions = ACTIVE.get(level);
        while (!deadlines.isEmpty() && deadlines.peek().dueTick() <= gameTime) {
            Session expired = deadlines.remove().session();
            // Successful/aborted sessions leave a cheap stale deadline behind.
            // Identity validation prevents that record from touching a newer pair.
            if (activeSessions == null || activeSessions.get(expired.parent1()) != expired) {
                continue;
            }

            Villager parent = findVillager(level, expired.parent1());
            if (parent == null) {
                parent = findVillager(level, expired.parent2());
            }
            if (parent != null) {
                abort(level, parent, AbortReason.ABORTED_TIMEOUT, true);
            } else {
                removeSession(level, expired);
                releaseBed(level, expired.bed());
            }
        }
        if (deadlines.isEmpty()) {
            DEADLINES.remove(level);
        }
    }

    public static void clearAll() {
        ACTIVE.clear();
        BLOCKED_PAIRS.clear();
        DEADLINES.clear();
    }

    public static void clearLevel(ServerLevel level) {
        ACTIVE.remove(level);
        BLOCKED_PAIRS.remove(level);
        DEADLINES.remove(level);
    }

    private static Optional<BlockPos> reserveBed(ServerLevel level, Villager villager) {
        return level.getPoiManager().take(
                poiType -> poiType.is(PoiTypes.HOME),
                (poiType, pos) -> isUsableBed(level, pos) && canReach(villager, pos, poiType),
                villager.blockPosition(),
                48);
    }

    private static boolean canReach(Villager villager, BlockPos pos, Holder<PoiType> poiType) {
        Path path = villager.getNavigation().createPath(pos, poiType.value().validRange());
        return path != null && path.canReach();
    }

    private static boolean isUsableBed(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(BlockTags.BEDS)) {
            return false;
        }
        return level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }

    private static void releaseBed(ServerLevel level, BlockPos bed) {
        if (level.getPoiManager().getType(bed).filter(type -> type.is(PoiTypes.HOME)).isPresent()) {
            level.getPoiManager().release(bed);
        }
    }

    private static void blockPair(ServerLevel level, Villager first, Villager second, long gameTime) {
        if (second == null) {
            return;
        }
        blockedPairs(level).put(PairKey.of(first.getUUID(), second.getUUID()),
                gameTime + PAIR_RETRY_COOLDOWN_TICKS);
        clearBreedingMemories(first, second);
    }

    private static void clearBreedingMemories(Villager first, Villager second) {
        clearBreedingMemories(first);
        clearBreedingMemories(second);
    }

    private static void clearBreedingMemories(Villager villager) {
        if (villager == null) return;
        villager.getBrain().eraseMemory(MemoryModuleType.BREED_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    private static Villager findVillager(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid) instanceof Villager villager ? villager : null;
    }

    private static Session removeSession(ServerLevel level, UUID first, UUID second) {
        Session session = sessions(level).get(first);
        if (session == null || !session.matches(first, second)) {
            return null;
        }
        removeSession(level, session);
        return session;
    }

    private static void removeSession(ServerLevel level, Session session) {
        sessions(level).remove(session.parent1());
        sessions(level).remove(session.parent2());
    }

    private static void cleanupExpiredPairCooldowns(ServerLevel level, long gameTime) {
        blockedPairs(level).entrySet().removeIf(entry -> entry.getValue() <= gameTime);
    }

    private static Map<UUID, Session> sessions(ServerLevel level) {
        return ACTIVE.computeIfAbsent(level, ignored -> new HashMap<>());
    }

    private static Map<PairKey, Long> blockedPairs(ServerLevel level) {
        return BLOCKED_PAIRS.computeIfAbsent(level, ignored -> new HashMap<>());
    }

    private static PriorityQueue<SessionDeadline> deadlines(ServerLevel level) {
        return DEADLINES.computeIfAbsent(level, ignored -> new PriorityQueue<>());
    }

    private record SessionDeadline(long dueTick, Session session) implements Comparable<SessionDeadline> {
        @Override
        public int compareTo(SessionDeadline other) {
            return Long.compare(dueTick, other.dueTick);
        }
    }

    private static final class Session {
        private final UUID parent1;
        private final UUID parent2;
        private final BlockPos bed;
        private final long expiresAt;
        private final PairKey pair;
        private boolean committed;

        private Session(UUID parent1, UUID parent2, BlockPos bed, long expiresAt, PairKey pair) {
            this.parent1 = parent1;
            this.parent2 = parent2;
            this.bed = bed;
            this.expiresAt = expiresAt;
            this.pair = pair;
        }

        private UUID parent1() {
            return parent1;
        }

        private UUID parent2() {
            return parent2;
        }

        private BlockPos bed() {
            return bed;
        }

        private long expiresAt() {
            return expiresAt;
        }

        private boolean committed() {
            return committed;
        }

        private void markCommitted() {
            committed = true;
        }

        private boolean matches(UUID first, UUID second) {
            return pair.equals(PairKey.of(first, second));
        }

        private UUID other(UUID uuid) {
            return parent1.equals(uuid) ? parent2 : parent1;
        }
    }

    private record PairKey(UUID first, UUID second) {
        private static PairKey of(UUID first, UUID second) {
            return first.compareTo(second) <= 0
                    ? new PairKey(first, second)
                    : new PairKey(second, first);
        }
    }
}
