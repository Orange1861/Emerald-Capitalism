package com.orangevillager61.emeraldcapitalism.navigation;

import java.util.Objects;

/**
 * The immutable part of a movement request.
 *
 * <p>This class deliberately has no Minecraft dependency so the ownership
 * rules can be tested without starting a game. Position, speed, and policy
 * identities are all part of intent; a nearby position, a speed-only change,
 * or a look-target change is therefore not treated as an idempotent
 * resubmission.</p>
 */
public record MovementIntent(
        String owner,
        String destinationIdentity,
        long destination,
        int speedBits,
        int closeEnough,
        String arrivalPolicy,
        String traversalPolicy,
        int priority,
        boolean hasLookTarget,
        long lookTarget,
        boolean clearLookTargetOnHandoff) {

    public MovementIntent(String owner, String destinationIdentity, long destination,
                          int speedBits, int closeEnough, String arrivalPolicy,
                          String traversalPolicy, int priority) {
        this(owner, destinationIdentity, destination, speedBits, closeEnough,
                arrivalPolicy, traversalPolicy, priority, false, 0L, false);
    }

    public MovementIntent(String owner, String destinationIdentity, long destination,
                          int speedBits, int closeEnough, String arrivalPolicy,
                          String traversalPolicy, int priority, boolean hasLookTarget,
                          long lookTarget) {
        this(owner, destinationIdentity, destination, speedBits, closeEnough,
                arrivalPolicy, traversalPolicy, priority, hasLookTarget, lookTarget, false);
    }

    public MovementIntent {
        owner = requireText(owner, "owner");
        destinationIdentity = requireText(destinationIdentity, "destinationIdentity");
        arrivalPolicy = requireText(arrivalPolicy, "arrivalPolicy");
        traversalPolicy = requireText(traversalPolicy, "traversalPolicy");
        if (closeEnough < 0) {
            throw new IllegalArgumentException("closeEnough must be non-negative");
        }
        if (!hasLookTarget && lookTarget != 0L) {
            throw new IllegalArgumentException("lookTarget must be zero when hasLookTarget is false");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
