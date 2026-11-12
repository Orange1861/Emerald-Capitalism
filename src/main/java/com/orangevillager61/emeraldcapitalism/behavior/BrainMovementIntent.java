package com.orangevillager61.emeraldcapitalism.behavior;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Publishes one running Brain behavior's movement intent as vanilla {@code WALK_TARGET} and
 * {@code LOOK_TARGET} memories.
 *
 * <p>Caller: {@link AvoidBoatBehavior}. Vanilla {@code MoveToTargetSink} plans and walks the
 * route; this class never creates a path, writes {@code PATH}, or touches navigation. It reads
 * the sink's outcome back from the memories, and {@link #release} erases only the exact values
 * this instance published, so a stopped behavior cannot clear a newer target.
 *
 * <p>Vanilla builds behavior instances per villager Brain, so one instance holds one villager's
 * transient intent. Nothing here is persisted.
 */
public final class BrainMovementIntent {

    /** What the published walk target looks like on this tick. */
    public enum Status {
        /** Nothing published since the last release. */
        NONE,
        /** The published target is still in {@code WALK_TARGET}. */
        MOVING,
        /** The sink consumed the target and the villager is within its close-enough distance. */
        ARRIVED,
        /** The sink consumed the target short of it while the destination was still reachable. */
        ROUTE_ENDED,
        /** The sink consumed the target and recorded {@code CANT_REACH_WALK_TARGET_SINCE}. */
        UNREACHABLE,
        /** Another behavior wrote a different {@code WALK_TARGET}. */
        REPLACED,
        /** The target is still published but the journey exceeded its time limit. */
        TIMED_OUT
    }

    private final String owner;
    private final boolean outranksLegacyRequests;
    private final long timeoutTicks;
    @Nullable
    private OwnedWalkTarget walkTarget;
    @Nullable
    private OwnedLookTarget lookTarget;
    private long publishedAt;

    /**
     * @param owner                  identifies the publishing behavior in inspection output
     * @param outranksLegacyRequests true for a safety behavior: the transitional
     *                               {@code MovementRequestManager} defers to this target instead
     *                               of reclaiming {@code WALK_TARGET} for a legacy work goal
     * @param timeoutTicks           how long one destination may stay published before
     *                               {@link Status#TIMED_OUT}
     */
    public BrainMovementIntent(String owner, boolean outranksLegacyRequests, long timeoutTicks) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.outranksLegacyRequests = outranksLegacyRequests;
        this.timeoutTicks = timeoutTicks;
    }

    /**
     * Publishes a block destination. Republishing the same destination keeps the journey's start
     * time, so a target restored after replacement does not extend the time limit.
     */
    public void walkTo(Villager villager, BlockPos destination, float speedModifier, int closeEnough,
                       long gameTime) {
        Brain<?> brain = villager.getBrain();
        boolean sameRequest = walkTarget != null && walkTarget.matches(destination, speedModifier, closeEnough);
        if (sameRequest && brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == walkTarget) {
            return;
        }
        walkTarget = new OwnedWalkTarget(this, destination.immutable(), speedModifier, closeEnough);
        brain.setMemory(MemoryModuleType.WALK_TARGET, walkTarget);
        if (!sameRequest) {
            publishedAt = gameTime;
        }
    }

    /**
     * Looks at {@code entity} while the intent is active. A look target written by another
     * behavior is left alone; vanilla's look sink erasing ours lets the next call publish again.
     */
    public void lookAt(Villager villager, Entity entity) {
        Brain<?> brain = villager.getBrain();
        Object current = brain.getMemory(MemoryModuleType.LOOK_TARGET).orElse(null);
        if (current != null && current != lookTarget) {
            return;
        }
        if (current == lookTarget && lookTarget != null && lookTarget.getEntity() == entity) {
            return;
        }
        lookTarget = new OwnedLookTarget(this, entity);
        brain.setMemory(MemoryModuleType.LOOK_TARGET, lookTarget);
    }

    public Status observe(Villager villager, long gameTime) {
        OwnedWalkTarget published = walkTarget;
        if (published == null) {
            return Status.NONE;
        }
        Brain<?> brain = villager.getBrain();
        WalkTarget current = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (current == published) {
            return gameTime - publishedAt > timeoutTicks ? Status.TIMED_OUT : Status.MOVING;
        }
        if (current != null) {
            return Status.REPLACED;
        }
        // Same arrival rule as MoveToTargetSink.reachedTarget.
        if (published.getTarget().currentBlockPosition().distManhattan(villager.blockPosition())
                <= published.getCloseEnoughDist()) {
            return Status.ARRIVED;
        }
        return brain.hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                ? Status.UNREACHABLE : Status.ROUTE_ENDED;
    }

    /** True while this instance's walk target is the villager's current {@code WALK_TARGET}. */
    public boolean ownsWalkTarget(Villager villager) {
        return walkTarget != null
                && villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == walkTarget;
    }

    @Nullable
    public BlockPos destination() {
        return walkTarget == null ? null : walkTarget.getTarget().currentBlockPosition();
    }

    /** Erases only the exact memory values this instance published, then forgets them. */
    public void release(Villager villager) {
        Brain<?> brain = villager.getBrain();
        if (walkTarget != null && brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == walkTarget) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        if (lookTarget != null && brain.getMemory(MemoryModuleType.LOOK_TARGET).orElse(null) == lookTarget) {
            brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
        walkTarget = null;
        lookTarget = null;
    }

    /**
     * Transitional bridge read by {@code MovementRequestManager} until step 15 deletes it: a
     * safety intent in {@code WALK_TARGET} must not be reclaimed, snapshotted, or restored by a
     * legacy work request.
     */
    public static boolean outranksLegacyRequests(@Nullable WalkTarget target) {
        return target instanceof OwnedWalkTarget owned && owned.intent.outranksLegacyRequests;
    }

    /** True for a walk or look target published by any intent; such values are never restored. */
    public static boolean isPublishedIntent(@Nullable Object memoryValue) {
        return memoryValue instanceof OwnedWalkTarget || memoryValue instanceof OwnedLookTarget;
    }

    /** The publishing behavior's owner name, or null for a value no intent published. Read-only. */
    @Nullable
    public static String ownerOf(@Nullable Object memoryValue) {
        if (memoryValue instanceof OwnedWalkTarget owned) {
            return owned.intent.owner;
        }
        if (memoryValue instanceof OwnedLookTarget owned) {
            return owned.intent.owner;
        }
        return null;
    }

    /** Identity carrier: vanilla stores memory values by reference, so the subclass marks ownership. */
    private static final class OwnedWalkTarget extends WalkTarget {
        private final BrainMovementIntent intent;

        private OwnedWalkTarget(BrainMovementIntent intent, BlockPos destination, float speedModifier,
                                int closeEnough) {
            super(destination, speedModifier, closeEnough);
            this.intent = intent;
        }

        private boolean matches(BlockPos destination, float speedModifier, int closeEnough) {
            return getTarget().currentBlockPosition().equals(destination)
                    && Float.compare(getSpeedModifier(), speedModifier) == 0
                    && getCloseEnoughDist() == closeEnough;
        }
    }

    private static final class OwnedLookTarget extends EntityTracker {
        private final BrainMovementIntent intent;

        private OwnedLookTarget(BrainMovementIntent intent, Entity entity) {
            super(entity, true);
            this.intent = intent;
        }
    }
}
