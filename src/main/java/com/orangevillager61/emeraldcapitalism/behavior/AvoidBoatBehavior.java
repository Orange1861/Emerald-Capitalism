package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
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
 *
 * <p>Movement intent: the behavior only publishes {@code WALK_TARGET} and
 * {@code LOOK_TARGET} through {@link BrainMovementIntent}. Vanilla
 * {@code MoveToTargetSink} plans and walks the route, so this class neither
 * preflights paths nor calls navigation. Escape targets are chosen from block
 * collision checks alone; vanilla's own fallback route covers an unreachable
 * one, and a fresh pick replaces it within ten ticks.</p>
 */
public class AvoidBoatBehavior extends Behavior<Villager> {

    private static final double BOAT_DETECTION_RANGE = 8.0D;
    private static final double MIN_SAFE_DISTANCE = 4.5D;
    private static final double PUSH_AWAY_DISTANCE = 3.0D;
    private static final float SPEED_MODIFIER = 0.7F;
    private static final int CLOSE_ENOUGH = 0;
    private static final long NEARBY_BOAT_LOOKUP_INTERVAL_TICKS = 5L;
    private static final long EMPTY_BOAT_LOOKUP_INTERVAL_TICKS = 20L;
    private static final long REPICK_INTERVAL_TICKS = 10L;
    /** Matches the behavior's maximum duration; a journey this long means the escape is stuck. */
    private static final long INTENT_TIMEOUT_TICKS = 80L;
    /** Escape directions tried in order, as rotations of the straight-away direction. */
    private static final double[] ESCAPE_ANGLES = {0.0D, 0.6D, -0.6D, 1.2D, -1.2D};
    private static final double[] ESCAPE_DISTANCES = {PUSH_AWAY_DISTANCE, PUSH_AWAY_DISTANCE + 2.0D};

    @Nullable
    private UUID cachedVillagerId;
    @Nullable
    private Boat cachedClosestBoat;
    private long nextBoatLookupTick = Long.MIN_VALUE;
    private long nextRepickTick = Long.MIN_VALUE;
    private final BrainMovementIntent intent =
            new BrainMovementIntent("emeraldcapitalism:boat_avoidance", true, INTENT_TIMEOUT_TICKS);

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
        nextRepickTick = gameTime;
        publishEscape(villager, gameTime);
    }

    @Override
    protected void tick(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        publishEscape(villager, gameTime);
    }

    @Override
    protected void stop(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        intent.release(villager);
    }

    @Override
    protected boolean canStillUse(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        if (!Config.enableBoatAvoidance || VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return false;
        }
        Boat boat = findClosestBoat(villager);
        if (boat == null || !isTooClose(villager, boat)) {
            return false;
        }
        // Another behavior published a different WALK_TARGET (panic, a higher-priority task):
        // yield instead of overwriting it.
        return intent.observe(villager, gameTime) != BrainMovementIntent.Status.REPLACED;
    }

    private void publishEscape(Villager villager, long gameTime) {
        Boat boat = findClosestBoat(villager);
        if (boat == null || !isTooClose(villager, boat)) {
            return;
        }
        intent.lookAt(villager, boat);

        BrainMovementIntent.Status status = intent.observe(villager, gameTime);
        boolean needsTarget = status != BrainMovementIntent.Status.MOVING;
        if (!needsTarget && gameTime < nextRepickTick) {
            return;
        }
        // Re-pick while moving too, so a boat that drifts is not escaped toward its new side.
        BlockPos escape = chooseEscape(villager, boat);
        nextRepickTick = gameTime + REPICK_INTERVAL_TICKS;
        if (escape != null) {
            intent.walkTo(villager, escape, SPEED_MODIFIER, CLOSE_ENOUGH, gameTime);
        }
    }

    @Nullable
    private static BlockPos chooseEscape(Villager villager, Boat boat) {
        Vec3 boatPos = boat.position();
        Vec3 villagerPos = villager.position();
        Vec3 away = new Vec3(villagerPos.x - boatPos.x, 0.0D, villagerPos.z - boatPos.z);
        if (away.lengthSqr() < 1.0E-6D) {
            away = new Vec3(villager.getRandom().nextDouble() - 0.5D, 0.0D,
                    villager.getRandom().nextDouble() - 0.5D);
        }
        Vec3 direction = away.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : away.normalize();

        BlockPos fallback = null;
        for (double distance : ESCAPE_DISTANCES) {
            for (double angle : ESCAPE_ANGLES) {
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                Vec3 rotated = new Vec3(direction.x * cos - direction.z * sin, 0.0D,
                        direction.x * sin + direction.z * cos);
                BlockPos desired = BlockPos.containing(villagerPos.add(rotated.scale(distance)));
                if (fallback == null) {
                    fallback = desired;
                }
                BlockPos standable = findStandable(villager, desired);
                if (standable != null && farEnoughFromBoat(standable, boatPos)) {
                    return standable;
                }
            }
        }
        // Vanilla's sink falls back to a nearby route when the exact block is unusable.
        return fallback;
    }

    private static boolean farEnoughFromBoat(BlockPos candidate, Vec3 boatPos) {
        double dx = candidate.getX() + 0.5D - boatPos.x;
        double dz = candidate.getZ() + 0.5D - boatPos.z;
        return dx * dx + dz * dz >= MIN_SAFE_DISTANCE * MIN_SAFE_DISTANCE;
    }

    @Nullable
    private static BlockPos findStandable(Villager villager, BlockPos target) {
        for (int dy : new int[] {0, 1, -1}) {
            BlockPos candidate = target.above(dy);
            if (isClearEscapeTarget(villager, candidate)) {
                return candidate;
            }
        }
        return null;
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
