package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerNavigationTargets;
import com.orangevillager61.emeraldcapitalism.world.village.VillageHostility;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Makes villagers flee visible hostile players while preferring to stay in their village. */
public final class FleeHostileVillagePlayerBehavior extends Behavior<Villager> {

    private static final double DETECTION_RANGE = 16.0D;
    private static final double MIN_SAFE_DISTANCE = 8.0D;
    private static final double ESCAPE_DISTANCE = 8.0D;
    private static final float SPEED_MODIFIER = 1.35F;
    private static final long THREAT_LOOKUP_INTERVAL_TICKS = 5L;
    private static final long EMPTY_LOOKUP_INTERVAL_TICKS = 20L;
    private static final int IN_VILLAGE_SEARCH_RADIUS = 4;
    private static final int OUTSIDE_VILLAGE_SEARCH_RADIUS = 4;

    @Nullable
    private ServerPlayer cachedThreat;
    @Nullable
    private VillageRecord village;
    @Nullable
    private BlockPos escapeTarget;
    private long nextThreatLookupTick = Long.MIN_VALUE;
    private long nextEscapeSearchTick = Long.MIN_VALUE;

    public FleeHostileVillagePlayerBehavior() {
        super(
                Map.of(
                        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
                ),
                40,
                80
        );
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        village = VillageHostility.findVillage(level, villager.blockPosition());
        ServerPlayer threat = findClosestThreat(level, villager);
        return village != null && threat != null;
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        escapeTarget = null;
        nextEscapeSearchTick = gameTime;
        updatePathAwayFromThreat(level, villager, gameTime);
    }

    @Override
    protected void tick(ServerLevel level, Villager villager, long gameTime) {
        updatePathAwayFromThreat(level, villager, gameTime);
    }

    @Override
    protected void stop(ServerLevel level, Villager villager, long gameTime) {
        cachedThreat = null;
        village = null;
        escapeTarget = null;
        nextThreatLookupTick = Long.MIN_VALUE;
        nextEscapeSearchTick = Long.MIN_VALUE;
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        return findClosestThreat(level, villager) != null;
    }

    private void updatePathAwayFromThreat(ServerLevel level, Villager villager, long gameTime) {
        ServerPlayer threat = findClosestThreat(level, villager);
        if (threat == null || village == null) {
            return;
        }

        Vec3 away = villager.position().subtract(threat.position());
        if (away.lengthSqr() < 1.0E-6D) {
            away = new Vec3(villager.getRandom().nextDouble() - 0.5D, 0.0D,
                    villager.getRandom().nextDouble() - 0.5D);
        }
        Vec3 normalizedAway = new Vec3(away.x, 0.0D, away.z).normalize();
        if (normalizedAway.lengthSqr() < 1.0E-6D) {
            normalizedAway = new Vec3(1.0D, 0.0D, 0.0D);
        }

        Vec3 preferredTarget = villager.position().add(normalizedAway.scale(ESCAPE_DISTANCE));
        BlockPos villageTarget = clampToVillage(village.getBoundingBox(), preferredTarget);
        if (escapeTarget == null || gameTime >= nextEscapeSearchTick) {
            escapeTarget = findInVillageEscapeTarget(villager, villageTarget, threat, village);
            if (escapeTarget == null) {
                BlockPos outsideTarget = BlockPos.containing(preferredTarget);
                escapeTarget = VillagerNavigationTargets.findReachableTarget(
                        villager,
                        outsideTarget,
                        OUTSIDE_VILLAGE_SEARCH_RADIUS,
                        candidate -> isSafeEscapeTarget(villager, candidate, threat));
                if (escapeTarget == null) {
                    escapeTarget = findClearEscapeTarget(villager, outsideTarget, threat, null);
                }
            }
            nextEscapeSearchTick = gameTime + 10L;
        }

        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(threat, true));
        if (escapeTarget != null) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(new BlockPosTracker(escapeTarget), SPEED_MODIFIER, 0));
        } else {
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    @Nullable
    private static BlockPos findInVillageEscapeTarget(Villager villager, BlockPos desiredTarget,
                                                       ServerPlayer threat, VillageRecord village) {
        BlockPos reachable = VillagerNavigationTargets.findReachableTarget(
                villager,
                desiredTarget,
                IN_VILLAGE_SEARCH_RADIUS,
                candidate -> isInsideVillage(village.getBoundingBox(), candidate)
                        && isSafeEscapeTarget(villager, candidate, threat));
        if (reachable != null) {
            return reachable;
        }
        return findClearEscapeTarget(villager, desiredTarget, threat, village.getBoundingBox());
    }

    private static boolean isSafeEscapeTarget(Villager villager, BlockPos target,
                                              ServerPlayer threat) {
        double dx = target.getX() + 0.5D - threat.getX();
        double dz = target.getZ() + 0.5D - threat.getZ();
        return dx * dx + dz * dz >= MIN_SAFE_DISTANCE * MIN_SAFE_DISTANCE;
    }

    private static boolean isInsideVillage(AABB bounds, BlockPos target) {
        return bounds.contains(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
    }

    private static boolean isClearEscapeTarget(Villager villager, BlockPos target,
                                               ServerPlayer threat, @Nullable AABB preferredBounds) {
        return (preferredBounds == null || isInsideVillage(preferredBounds, target))
                && isSafeEscapeTarget(villager, target, threat)
                && villager.level().getBlockState(target).getCollisionShape(villager.level(), target).isEmpty()
                && villager.level().getBlockState(target.above())
                .getCollisionShape(villager.level(), target.above()).isEmpty()
                && !villager.level().getBlockState(target.below())
                .getCollisionShape(villager.level(), target.below()).isEmpty();
    }

    @Nullable
    private static BlockPos findClearEscapeTarget(Villager villager, BlockPos desiredTarget,
                                                  ServerPlayer threat, @Nullable AABB preferredBounds) {
        int radius = preferredBounds == null
                ? OUTSIDE_VILLAGE_SEARCH_RADIUS : IN_VILLAGE_SEARCH_RADIUS;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = desiredTarget.offset(dx, dy, dz);
                    if (isClearEscapeTarget(villager, candidate, threat, preferredBounds)) {
                        return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos clampToVillage(AABB bounds, Vec3 target) {
        double minX = bounds.minX + 0.5D;
        double maxX = Math.max(minX, bounds.maxX - 0.5D);
        double minY = bounds.minY + 0.5D;
        double maxY = Math.max(minY, bounds.maxY - 0.5D);
        double minZ = bounds.minZ + 0.5D;
        double maxZ = Math.max(minZ, bounds.maxZ - 0.5D);
        return BlockPos.containing(
                clamp(target.x, minX, maxX),
                clamp(target.y, minY, maxY),
                clamp(target.z, minZ, maxZ));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Nullable
    private ServerPlayer findClosestThreat(ServerLevel level, Villager villager) {
        long gameTime = level.getGameTime();
        if (gameTime < nextThreatLookupTick) {
            if (cachedThreat == null) {
                // Empty lookups are cached too; otherwise every active behavior
                // check would repeat the nearby-player scan before its expiry.
                return null;
            }
            if (cachedThreat.isAlive()
                    && village != null
                    && VillageHostility.isHostilePlayer(level, village, cachedThreat)
                    && villager.hasLineOfSight(cachedThreat)
                    && villager.distanceToSqr(cachedThreat) <= DETECTION_RANGE * DETECTION_RANGE) {
                return cachedThreat;
            }
        }

        if (village == null) {
            village = VillageHostility.findVillage(level, villager.blockPosition());
        }
        if (village == null) {
            cachedThreat = null;
            nextThreatLookupTick = gameTime + EMPTY_LOOKUP_INTERVAL_TICKS;
            return null;
        }

        cachedThreat = VillageHostility.findClosestVisibleHostilePlayer(
                level, village, villager, DETECTION_RANGE);
        nextThreatLookupTick = gameTime + (cachedThreat == null
                ? EMPTY_LOOKUP_INTERVAL_TICKS : THREAT_LOOKUP_INTERVAL_TICKS);
        return cachedThreat;
    }
}
