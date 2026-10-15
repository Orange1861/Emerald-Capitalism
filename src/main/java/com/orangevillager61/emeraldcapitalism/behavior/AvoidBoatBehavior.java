package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerNavigationTargets;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestHandle;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSpec;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSubmission;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestTerminationReason;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Brain behavior that makes villagers keep distance from nearby boats.
 *
 * <p>All wood variants are covered because they are instances of {@link Boat}
 * (including chest boats in supported versions).</p>
 */
public class AvoidBoatBehavior extends Behavior<Villager> {

    private static final double BOAT_DETECTION_RANGE = 8.0D;
    private static final double MIN_SAFE_DISTANCE = 4.5D;
    private static final double PUSH_AWAY_DISTANCE = 3.0D;
    private static final float SPEED_MODIFIER = 0.7F;
    private static final long NEARBY_BOAT_LOOKUP_INTERVAL_TICKS = 5L;
    private static final long EMPTY_BOAT_LOOKUP_INTERVAL_TICKS = 20L;

    @Nullable
    private UUID cachedVillagerId;
    @Nullable
    private Boat cachedClosestBoat;
    private long nextBoatLookupTick = Long.MIN_VALUE;
    @Nullable
    private BlockPos escapeTarget;
    private long nextEscapeSearchTick = Long.MIN_VALUE;
    @Nullable
    private MovementRequestHandle movementHandle;
    @Nullable
    private MovementRequestSpec cachedMovementSpec;
    @Nullable
    private Boat lookTargetBoat;
    @Nullable
    private EntityTracker lookTargetTracker;

    public AvoidBoatBehavior() {
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
    protected boolean checkExtraStartConditions(@Nonnull ServerLevel level, @Nonnull Villager villager) {
        if (!Config.enableBoatAvoidance || VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return false;
        }
        Boat boat = findClosestBoat(villager);
        return boat != null && isTooClose(villager, boat);
    }

    @Override
    protected void start(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        escapeTarget = null;
        nextEscapeSearchTick = gameTime;
        updatePathAwayFromBoat(villager, gameTime);
    }

    @Override
    protected void tick(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        updatePathAwayFromBoat(villager, gameTime);
    }

    @Override
    protected void stop(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        MovementRequestTerminationReason reason = escapeTarget != null
                && villager.blockPosition().distSqr(escapeTarget) <= 4.0D
                ? MovementRequestTerminationReason.ARRIVED
                : MovementRequestTerminationReason.ABANDONED;
        if (movementHandle != null) {
            MovementRequestManager.cancel(villager, movementHandle, reason);
            movementHandle = null;
        }
        escapeTarget = null;
        cachedMovementSpec = null;
        lookTargetBoat = null;
        lookTargetTracker = null;
    }

    @Override
    protected boolean canStillUse(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        if (!Config.enableBoatAvoidance || VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return false;
        }
        Boat boat = findClosestBoat(villager);
        return boat != null && isTooClose(villager, boat)
                && movementHandle != null
                && MovementRequestManager.isActive(villager, movementHandle);
    }

    private void updatePathAwayFromBoat(Villager villager, long gameTime) {
        Boat boat = findClosestBoat(villager);
        if (boat == null || !isTooClose(villager, boat)) {
            return;
        }

        Vec3 boatPos = boat.position();
        Vec3 villagerPos = villager.position();
        Vec3 away = villagerPos.subtract(boatPos);

        if (away.lengthSqr() < 1.0E-6D) {
            away = new Vec3(villager.getRandom().nextDouble() - 0.5D, 0.0D, villager.getRandom().nextDouble() - 0.5D);
        }

        Vec3 normalizedAway = new Vec3(away.x, 0.0D, away.z).normalize();
        if (normalizedAway.lengthSqr() < 1.0E-6D) {
            normalizedAway = new Vec3(1.0D, 0.0D, 0.0D);
        }

        Vec3 targetVec = villagerPos.add(normalizedAway.scale(PUSH_AWAY_DISTANCE));
        BlockPos desiredTarget = BlockPos.containing(targetVec);

        if (escapeTarget == null || gameTime >= nextEscapeSearchTick) {
            escapeTarget = VillagerNavigationTargets.findReachableTargetOrFallback(
                    villager,
                    desiredTarget,
                    3,
                    candidate -> {
                        double dx = candidate.getX() + 0.5D - boatPos.x;
                        double dz = candidate.getZ() + 0.5D - boatPos.z;
                        return dx * dx + dz * dz >= MIN_SAFE_DISTANCE * MIN_SAFE_DISTANCE;
                    });
            if (escapeTarget == null && isClearEscapeTarget(villager, desiredTarget)) {
                // Keep a safe, empty fallback when path creation is temporarily
                // unavailable (for example while a neighboring chunk is loading).
                escapeTarget = desiredTarget;
            }
            nextEscapeSearchTick = gameTime + 10L;
        }

        if (escapeTarget != null) {
            MovementRequestSpec spec = cachedMovementSpec;
            if (spec == null || !spec.destination().equals(escapeTarget)) {
                spec = new MovementRequestSpec(
                        "emeraldcapitalism:boat_avoidance",
                        "boat-escape",
                        escapeTarget,
                        SPEED_MODIFIER,
                        0,
                        "boat-avoidance-v1",
                        "ground-ladder-v1",
                        MovementRequestManager.PRIORITY_ESCAPE - 10,
                        null);
                cachedMovementSpec = spec;
            }
            MovementRequestSubmission submission = movementHandle == null
                    ? MovementRequestManager.submit(villager, spec)
                    : MovementRequestManager.maintain(villager, movementHandle, spec);
            if (submission.accepted()) {
                movementHandle = submission.handle();
                MovementRequestManager.claimLookTarget(villager, movementHandle,
                        lookTargetTracker(boat));
            } else {
                movementHandle = null;
            }
        } else {
            if (movementHandle != null) {
                MovementRequestManager.cancel(villager, movementHandle,
                        MovementRequestTerminationReason.FAILED);
                movementHandle = null;
            }
        }
    }

    private EntityTracker lookTargetTracker(Boat boat) {
        if (lookTargetTracker == null || lookTargetBoat != boat) {
            lookTargetBoat = boat;
            lookTargetTracker = new EntityTracker(boat, true);
        }
        return lookTargetTracker;
    }

    private static boolean isClearEscapeTarget(Villager villager, BlockPos target) {
        return villager.level().getBlockState(target).getCollisionShape(villager.level(), target).isEmpty()
                && villager.level().getBlockState(target.above())
                .getCollisionShape(villager.level(), target.above()).isEmpty()
                && !villager.level().getBlockState(target.below())
                .getCollisionShape(villager.level(), target.below()).isEmpty();
    }

    private static boolean isTooClose(Villager villager, Boat boat) {
        return villager.distanceToSqr(boat) < (MIN_SAFE_DISTANCE * MIN_SAFE_DISTANCE);
    }

    @Nullable
    private Boat findClosestBoat(Villager villager) {
        long gameTime = villager.level().getGameTime();
        if (gameTime < nextBoatLookupTick && villager.getUUID().equals(cachedVillagerId)) {
            return cachedClosestBoat != null && cachedClosestBoat.isAlive() ? cachedClosestBoat : null;
        }

        List<Boat> nearbyBoats = villager.level().getEntitiesOfClass(
                Boat.class,
                villager.getBoundingBox().inflate(BOAT_DETECTION_RANGE),
                Boat::isAlive
        );
        Boat closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Boat boat : nearbyBoats) {
            double distance = villager.distanceToSqr(boat);
            if (distance < closestDistance) {
                closest = boat;
                closestDistance = distance;
            }
        }
        cachedVillagerId = villager.getUUID();
        cachedClosestBoat = closest;
        nextBoatLookupTick = gameTime + (closest == null
                ? EMPTY_BOAT_LOOKUP_INTERVAL_TICKS
                : NEARBY_BOAT_LOOKUP_INTERVAL_TICKS);
        return closest;
    }
}
