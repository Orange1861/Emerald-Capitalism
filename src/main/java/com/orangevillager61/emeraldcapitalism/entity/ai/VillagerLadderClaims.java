package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** UUID-based, dimension-aware ownership for one-villager-at-a-time ladder columns. */
public final class VillagerLadderClaims {
    private static final Map<LadderColumn, UUID> CLAIMS = new ConcurrentHashMap<>();

    private VillagerLadderClaims() {
    }

    public record LadderColumn(ResourceKey<Level> dimension, int x, int z) {
    }

    public static LadderColumn column(Villager villager, BlockPos pos) {
        return new LadderColumn(villager.level().dimension(), pos.getX(), pos.getZ());
    }

    public static boolean tryClaim(Villager villager, LadderColumn column) {
        UUID owner = CLAIMS.get(column);
        if (owner == null || owner.equals(villager.getUUID()) || isStale(villager, column, owner)) {
            CLAIMS.put(column, villager.getUUID());
            return true;
        }
        return false;
    }

    public static void release(Villager villager, LadderColumn column) {
        if (column != null) {
            CLAIMS.remove(column, villager.getUUID());
        }
    }

    public static void releaseAll(UUID villagerId) {
        CLAIMS.values().removeIf(villagerId::equals);
    }

    public static void clear() {
        CLAIMS.clear();
    }

    private static boolean isStale(Villager villager, LadderColumn column, UUID owner) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) {
            return true;
        }
        var entity = serverLevel.getEntity(owner);
        return !(entity instanceof LivingEntity living) || !entity.isAlive() || !living.onClimbable()
                || entity.blockPosition().getX() != column.x()
                || entity.blockPosition().getZ() != column.z();
    }
}
