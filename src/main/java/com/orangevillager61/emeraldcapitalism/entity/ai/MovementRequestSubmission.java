package com.orangevillager61.emeraldcapitalism.entity.ai;

import java.util.Objects;

/** Outcome and capability token from a request submission. */
public record MovementRequestSubmission(MovementRequestOutcome outcome,
                                        MovementRequestHandle handle) {
    public MovementRequestSubmission {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(handle, "handle");
    }

    public boolean accepted() {
        return outcome != MovementRequestOutcome.REJECTED_LOWER_PRIORITY
                && outcome != MovementRequestOutcome.REJECTED_NOT_HIGHER_PRIORITY
                && outcome != MovementRequestOutcome.REJECTED_STALE_HANDLE;
    }

    public boolean changed() {
        return accepted() && outcome != MovementRequestOutcome.UNCHANGED;
    }
}
