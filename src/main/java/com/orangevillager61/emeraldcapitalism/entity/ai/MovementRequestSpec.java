package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A validated request for the existing brain movement executor.
 *
 * <p>The request contains no route or traversal state. Those remain transient
 * executor concerns and are rebuilt when an authoritative task submits an
 * eligible intent after reload.</p>
 */
public record MovementRequestSpec(
        String owner,
        String destinationIdentity,
        BlockPos destination,
        float speedModifier,
        int closeEnough,
        String arrivalPolicy,
        String traversalPolicy,
        int priority,
        @Nullable BlockPos lookTarget) {

    public MovementRequestSpec {
        owner = requireText(owner, "owner");
        destinationIdentity = requireText(destinationIdentity, "destinationIdentity");
        arrivalPolicy = requireText(arrivalPolicy, "arrivalPolicy");
        traversalPolicy = requireText(traversalPolicy, "traversalPolicy");
        destination = Objects.requireNonNull(destination, "destination").immutable();
        if (!Float.isFinite(speedModifier) || speedModifier <= 0.0F) {
            throw new IllegalArgumentException("speedModifier must be finite and positive");
        }
        if (closeEnough < 0) {
            throw new IllegalArgumentException("closeEnough must be non-negative");
        }
        lookTarget = lookTarget == null ? null : lookTarget.immutable();
    }

    public boolean hasLookTarget() {
        return lookTarget != null;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
