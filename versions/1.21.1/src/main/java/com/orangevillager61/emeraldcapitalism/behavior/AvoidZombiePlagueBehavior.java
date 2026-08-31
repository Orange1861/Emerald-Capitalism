package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerNavigationTargets;
import com.orangevillager61.emeraldcapitalism.event.ZombieVirusEvents;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** Makes healthy and greening-phase villagers flee nearby turning-phase villagers. */
public final class AvoidZombiePlagueBehavior extends Behavior<Villager> {

    private static final double DETECTION_RANGE = 8.0D;
    private static final double MIN_SAFE_DISTANCE = 6.0D;
    private static final double ESCAPE_DISTANCE = 6.0D;
    private static final float SPEED_MODIFIER = 1.3F;
    private static final long THREAT_LOOKUP_INTERVAL_TICKS = 5L;
    private static final long EMPTY_LOOKUP_INTERVAL_TICKS = 20L;

    @Nullable
    private Villager cachedThreat;
    private long nextThreatLookupTick = Long.MIN_VALUE;
    @Nullable
    private BlockPos escapeTarget;
    private long nextEscapeSearchTick = Long.MIN_VALUE;

    public AvoidZombiePlagueBehavior() {
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
        if (ZombieVirusEvents.isPhaseTwo(villager.getEffect(ECAPEffects.ZOMBIE_VIRUS))) {
            return false;
        }
        Villager threat = findClosestThreat(villager);
        return threat != null && isTooClose(villager, threat);
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        escapeTarget = null;
        nextEscapeSearchTick = gameTime;
        updatePathAwayFromThreat(villager, gameTime);
    }

    @Override
    protected void tick(ServerLevel level, Villager villager, long gameTime) {
        updatePathAwayFromThreat(villager, gameTime);
    }

    @Override
    protected void stop(ServerLevel level, Villager villager, long gameTime) {
        cachedThreat = null;
        escapeTarget = null;
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        if (ZombieVirusEvents.isPhaseTwo(villager.getEffect(ECAPEffects.ZOMBIE_VIRUS))) {
            return false;
        }
        Villager threat = findClosestThreat(villager);
        return threat != null && isTooClose(villager, threat);
    }

    private void updatePathAwayFromThreat(Villager villager, long gameTime) {
        Villager threat = findClosestThreat(villager);
        if (threat == null || !isTooClose(villager, threat)) {
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

        Vec3 targetVec = villager.position().add(normalizedAway.scale(ESCAPE_DISTANCE));
        BlockPos desiredTarget = BlockPos.containing(targetVec);

        if (escapeTarget == null || gameTime >= nextEscapeSearchTick) {
            escapeTarget = VillagerNavigationTargets.findReachableTarget(
                    villager,
                    desiredTarget,
                    3,
                    candidate -> {
                        double dx = candidate.getX() + 0.5D - threat.getX();
                        double dz = candidate.getZ() + 0.5D - threat.getZ();
                        return dx * dx + dz * dz >= MIN_SAFE_DISTANCE * MIN_SAFE_DISTANCE;
                    });
            if (escapeTarget == null) {
                escapeTarget = findClearEscapeTarget(villager, desiredTarget, threat);
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

    private static boolean isClearEscapeTarget(Villager villager, BlockPos target, Villager threat) {
        double dx = target.getX() + 0.5D - threat.getX();
        double dz = target.getZ() + 0.5D - threat.getZ();
        return dx * dx + dz * dz >= MIN_SAFE_DISTANCE * MIN_SAFE_DISTANCE
                && villager.level().getBlockState(target).getCollisionShape(villager.level(), target).isEmpty()
                && villager.level().getBlockState(target.above())
                .getCollisionShape(villager.level(), target.above()).isEmpty()
                && !villager.level().getBlockState(target.below())
                .getCollisionShape(villager.level(), target.below()).isEmpty();
    }

    @Nullable
    private static BlockPos findClearEscapeTarget(Villager villager, BlockPos desiredTarget, Villager threat) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos candidate = desiredTarget.offset(dx, dy, dz);
                    if (isClearEscapeTarget(villager, candidate, threat)) {
                        return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private Villager findClosestThreat(Villager villager) {
        long gameTime = villager.level().getGameTime();
        if (gameTime < nextThreatLookupTick
                && cachedThreat != null
                && cachedThreat.isAlive()
                && isPhaseTwo(cachedThreat)) {
            return cachedThreat;
        }

        List<Villager> nearbyVillagers = villager.level().getEntitiesOfClass(
                Villager.class,
                villager.getBoundingBox().inflate(DETECTION_RANGE),
                candidate -> candidate != villager && candidate.isAlive() && isPhaseTwo(candidate));
        Villager closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Villager candidate : nearbyVillagers) {
            double distance = villager.distanceToSqr(candidate);
            if (distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        cachedThreat = closest;
        nextThreatLookupTick = gameTime + (closest == null
                ? EMPTY_LOOKUP_INTERVAL_TICKS
                : THREAT_LOOKUP_INTERVAL_TICKS);
        return closest;
    }

    private static boolean isPhaseTwo(Villager villager) {
        return ZombieVirusEvents.isPhaseTwo(villager.getEffect(ECAPEffects.ZOMBIE_VIRUS));
    }

    private static boolean isTooClose(Villager villager, Villager threat) {
        return villager.distanceToSqr(threat) < MIN_SAFE_DISTANCE * MIN_SAFE_DISTANCE;
    }
}
