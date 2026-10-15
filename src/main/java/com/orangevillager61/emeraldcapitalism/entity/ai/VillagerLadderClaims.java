package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Entity-session, dimension-aware ownership for one-villager-at-a-time ladder segments. */
public final class VillagerLadderClaims {
    private static final int MAX_SEGMENT_SCAN = 64;
    private static final Map<LadderColumn, Villager> CLAIMS = new ConcurrentHashMap<>();

    private VillagerLadderClaims() {
    }

    public record LadderColumn(ResourceKey<Level> dimension, int x, int z, int minY, int maxY) {
    }

    public static LadderColumn column(Villager villager, BlockPos pos) {
        int minY = pos.getY();
        int maxY = pos.getY();
        int steps = 0;
        while (steps++ < MAX_SEGMENT_SCAN
                && villager.level().getBlockState(new BlockPos(pos.getX(), maxY + 1, pos.getZ()))
                .is(BlockTags.CLIMBABLE)) {
            maxY++;
        }
        steps = 0;
        while (steps++ < MAX_SEGMENT_SCAN
                && villager.level().getBlockState(new BlockPos(pos.getX(), minY - 1, pos.getZ()))
                .is(BlockTags.CLIMBABLE)) {
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
    }

    /** Drops claims for a dimension before its server level is unloaded. */
    public static void releaseDimension(ResourceKey<Level> dimension) {
        CLAIMS.keySet().removeIf(column -> column.dimension().equals(dimension));
    }

    public static void clear() {
        CLAIMS.clear();
    }

    /** Removes owners that have unloaded, changed dimensions, or left the segment. */
    public static void purgeStale() {
        CLAIMS.entrySet().removeIf(entry -> isStale(entry.getKey(), entry.getValue()));
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
