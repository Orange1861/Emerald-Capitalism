package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Per-goal convenience wrapper that retains the current capability token.
 * Each goal instance has its own binding; the manager still arbitrates the
 * entity-wide request and protects cleanup across goal replacement.
 */
public final class VillagerMovementRequestBinding {

    @Nullable
    private MovementRequestHandle handle;
    @Nullable
    private MovementRequestSpec cachedSpec;

    public boolean submit(Villager villager, MovementRequestSpec spec) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(spec, "spec");
        if (cachedSpec != null && cachedSpec.equals(spec)) {
            spec = cachedSpec;
        }
        return submitResolved(villager, spec);
    }

    /**
     * Submits a field-level request without constructing a new record when the
     * destination and policies are unchanged from the previous tick.
     */
    public boolean submit(Villager villager, String owner, String destinationIdentity,
                          BlockPos destination, float speedModifier, int closeEnough,
                          String arrivalPolicy, String traversalPolicy, int priority,
                          @Nullable BlockPos lookTarget) {
        if (cachedSpec != null
                && cachedSpec.owner().equals(owner)
                && cachedSpec.destinationIdentity().equals(destinationIdentity)
                && cachedSpec.destination().equals(destination)
                && Float.compare(cachedSpec.speedModifier(), speedModifier) == 0
                && cachedSpec.closeEnough() == closeEnough
                && cachedSpec.arrivalPolicy().equals(arrivalPolicy)
                && cachedSpec.traversalPolicy().equals(traversalPolicy)
                && cachedSpec.priority() == priority
                && java.util.Objects.equals(cachedSpec.lookTarget(), lookTarget)) {
            return submitResolved(villager, cachedSpec);
        }
        return submitResolved(villager, new MovementRequestSpec(
                owner, destinationIdentity, destination, speedModifier, closeEnough,
                arrivalPolicy, traversalPolicy, priority, lookTarget));
    }

    private boolean submitResolved(Villager villager, MovementRequestSpec spec) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(spec, "spec");
        MovementRequestSubmission submission;
        if (handle != null) {
            if (!MovementRequestManager.isActive(villager, handle)) {
                handle = null;
                cachedSpec = null;
                return false;
            }
            submission = MovementRequestManager.maintain(villager, handle, spec);
        } else {
            submission = MovementRequestManager.submit(villager, spec);
        }
        if (!submission.accepted()) {
            handle = null;
            cachedSpec = null;
            return false;
        }
        handle = submission.handle();
        cachedSpec = spec;
        return true;
    }

    public boolean isActive(Villager villager) {
        Objects.requireNonNull(villager, "villager");
        if (handle == null) {
            return false;
        }
        return MovementRequestManager.isActive(villager, handle);
    }

    public void cancel(Villager villager) {
        Objects.requireNonNull(villager, "villager");
        if (handle != null) {
            MovementRequestManager.cancel(villager, handle);
            handle = null;
            cachedSpec = null;
        }
    }

    public void cancel(Villager villager, MovementRequestTerminationReason reason) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(reason, "reason");
        if (handle != null) {
            MovementRequestManager.cancel(villager, handle, reason);
            handle = null;
            cachedSpec = null;
        }
    }

    public void complete(Villager villager) {
        cancel(villager, MovementRequestTerminationReason.ARRIVED);
    }

    @Nullable
    public MovementRequestHandle handle() {
        return handle;
    }
}
