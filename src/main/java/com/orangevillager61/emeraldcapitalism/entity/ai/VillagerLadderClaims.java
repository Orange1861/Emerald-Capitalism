package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.WeakHashMap;

/** Entity-session, dimension-aware ownership for one-villager-at-a-time ladder segments. */
public final class VillagerLadderClaims {
    private static final int MAX_SEGMENT_SCAN = 64;
    private static final int MAX_PURGE_VISITS = 64;
    private static final Map<LadderColumn, Villager> CLAIMS = new ConcurrentHashMap<>();
    private static final Map<Villager, BlockPos> ACTIVE_EXITS = new WeakHashMap<>();
    private static Iterator<Map.Entry<LadderColumn, Villager>> purgeIterator;

    private VillagerLadderClaims() {
    }

    public record LadderColumn(ResourceKey<Level> dimension, int x, int z, int minY, int maxY) {
    }

    public static LadderColumn column(Villager villager, BlockPos pos) {
        PerformanceTimingCounters.recordEvent(
                PerformanceTimingCounters.Operation.LADDER_SEGMENT_SCAN);
        int minY = pos.getY();
        int maxY = pos.getY();
        int steps = 0;
        while (steps++ < MAX_SEGMENT_SCAN) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.LADDER_BLOCK_SCAN);
            if (!villager.level().getBlockState(new BlockPos(pos.getX(), maxY + 1, pos.getZ()))
                    .is(BlockTags.CLIMBABLE)) {
                break;
            }
            maxY++;
        }
        steps = 0;
        while (steps++ < MAX_SEGMENT_SCAN) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.LADDER_BLOCK_SCAN);
            if (!villager.level().getBlockState(new BlockPos(pos.getX(), minY - 1, pos.getZ()))
                    .is(BlockTags.CLIMBABLE)) {
                break;
            }
            minY--;
        }
        return new LadderColumn(villager.level().dimension(), pos.getX(), pos.getZ(), minY, maxY);
    }

    public static boolean tryClaim(Villager villager, LadderColumn column) {
        Villager owner = CLAIMS.get(column);
        if (owner == null || owner == villager || isStale(column, owner)) {
            CLAIMS.put(column, villager);
            return true;
        }
        return false;
    }

    public static void release(Villager villager, LadderColumn column) {
        if (column != null) {
            CLAIMS.remove(column, villager);
        }
    }

    public static void releaseAll(Villager villager) {
        CLAIMS.values().removeIf(owner -> owner == villager);
        ACTIVE_EXITS.remove(villager);
    }

    /** Records the validated route exit for diagnostics and traversal tests. */
    public static void setActiveExit(Villager villager, BlockPos exit) {
        ACTIVE_EXITS.put(villager, exit.immutable());
    }

    public static void clearActiveExit(Villager villager) {
        ACTIVE_EXITS.remove(villager);
    }

    public static BlockPos activeExit(Villager villager) {
        return ACTIVE_EXITS.get(villager);
    }

    /** Returns whether this exact cached segment is still owned by the villager. */
    public static boolean isOwnedBy(Villager villager, LadderColumn column) {
        return CLAIMS.get(column) == villager;
    }

    /** Test and diagnostics hook for observing ownership without mutating it. */
    public static LadderColumn claimedColumn(Villager villager) {
        for (Map.Entry<LadderColumn, Villager> entry : CLAIMS.entrySet()) {
            if (entry.getValue() == villager) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Invalidates cached segments when a ladder block changes in their column. */
    public static void invalidateAt(Level level, BlockPos pos) {
        CLAIMS.keySet().removeIf(column -> column.dimension().equals(level.dimension())
                && column.x() == pos.getX() && column.z() == pos.getZ());
    }

    /** Drops claims for a dimension before its server level is unloaded. */
    public static void releaseDimension(ResourceKey<Level> dimension) {
        CLAIMS.keySet().removeIf(column -> column.dimension().equals(dimension));
        ACTIVE_EXITS.entrySet().removeIf(entry -> entry.getKey().level().dimension().equals(dimension));
        purgeIterator = null;
    }

    public static void clear() {
        CLAIMS.clear();
        ACTIVE_EXITS.clear();
        purgeIterator = null;
    }

    /** Removes a bounded number of owners that have become stale. */
    public static int purgeStale() {
        return purgeStale(MAX_PURGE_VISITS);
    }

    /** Removes at most {@code maxVisits} entries for the periodic fallback. */
    public static int purgeStale(int maxVisits) {
        if (maxVisits <= 0 || CLAIMS.isEmpty()) {
            return 0;
        }
        if (purgeIterator == null || !purgeIterator.hasNext()) {
            purgeIterator = CLAIMS.entrySet().iterator();
        }
        int visits = 0;
        while (visits < maxVisits && purgeIterator.hasNext()) {
            Map.Entry<LadderColumn, Villager> entry = purgeIterator.next();
            visits++;
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.LADDER_PURGE_VISIT);
            if (isStale(entry.getKey(), entry.getValue())) {
                CLAIMS.remove(entry.getKey(), entry.getValue());
            }
        }
        if (!purgeIterator.hasNext()) {
            purgeIterator = null;
        }
        return visits;
    }

    /** Test and diagnostics hook; ownership itself remains entity-identity based. */
    public static int claimCount() {
        purgeStale();
        return CLAIMS.size();
    }

    private static boolean isStale(LadderColumn column, Villager owner) {
        BlockPos pos = owner.blockPosition();
        return !owner.isAlive() || !owner.level().dimension().equals(column.dimension())
                || pos.getX() != column.x() || pos.getZ() != column.z()
                || pos.getY() < column.minY() - 1 || pos.getY() > column.maxY() + 1;
    }
}
