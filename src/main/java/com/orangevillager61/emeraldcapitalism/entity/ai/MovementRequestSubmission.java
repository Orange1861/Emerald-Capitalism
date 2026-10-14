package com.orangevillager61.emeraldcapitalism.entity.ai;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Outcome and capability token from a request submission. */
public record MovementRequestSubmission(MovementRequestOutcome outcome,
                                        @Nullable MovementRequestHandle handle) {
    public MovementRequestSubmission {
        Objects.requireNonNull(outcome, "outcome");
        if (acceptedOutcome(outcome)) {
            Objects.requireNonNull(handle, "accepted submissions require a handle");
        } else if (handle != null) {
            throw new IllegalArgumentException("rejected submissions must not carry a handle");
        }
    }

    public boolean accepted() {
        return acceptedOutcome(outcome);
    }

    public boolean changed() {
        return accepted() && outcome != MovementRequestOutcome.UNCHANGED;
    }

    private static boolean acceptedOutcome(MovementRequestOutcome outcome) {
        return outcome != MovementRequestOutcome.REJECTED_LOWER_PRIORITY
                && outcome != MovementRequestOutcome.REJECTED_NOT_HIGHER_PRIORITY
                && outcome != MovementRequestOutcome.REJECTED_STALE_HANDLE;
    }
}
