package com.orangevillager61.emeraldcapitalism.navigation;

import java.util.Objects;

/**
 * Pure ownership ledger for one actor's movement execution path.
 *
 * <p>A ledger has no persistence semantics. The active request is transient;
 * callers reconstruct it from authoritative task data after a reload.</p>
 */
public final class MovementRequestLedger {

    public enum Outcome {
        ACCEPTED,
        UNCHANGED,
        REPLACED,
        PREEMPTED,
        REJECTED_LOWER_PRIORITY,
        REJECTED_NOT_HIGHER_PRIORITY
    }

    public record Handle(String owner, long generation) {
        public Handle {
            Objects.requireNonNull(owner, "owner");
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    public record Submission(Outcome outcome, Handle handle) {
        public Submission {
            Objects.requireNonNull(outcome, "outcome");
            if (isRejected(outcome)) {
                if (handle != null) {
                    throw new IllegalArgumentException("rejected submissions must not carry a handle");
                }
            } else {
                Objects.requireNonNull(handle, "accepted submissions require a handle");
            }
        }

        public boolean accepted() {
            return !isRejected(outcome);
        }

        public boolean changed() {
            return accepted() && outcome != Outcome.UNCHANGED;
        }

        private static boolean isRejected(Outcome outcome) {
            return outcome == Outcome.REJECTED_LOWER_PRIORITY
                    || outcome == Outcome.REJECTED_NOT_HIGHER_PRIORITY;
        }
    }

    private MovementIntent activeIntent;
    private Handle activeHandle;
    private long nextGeneration;

    public Submission submit(MovementIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (activeIntent != null && activeIntent.equals(intent)) {
            return new Submission(Outcome.UNCHANGED, activeHandle);
        }

        if (activeIntent != null
                && !activeIntent.owner().equals(intent.owner())
                && intent.priority() <= activeIntent.priority()) {
            Outcome outcome = intent.priority() < activeIntent.priority()
                    ? Outcome.REJECTED_LOWER_PRIORITY
                    : Outcome.REJECTED_NOT_HIGHER_PRIORITY;
            return new Submission(outcome, null);
        }

        Outcome outcome = activeIntent == null
                ? Outcome.ACCEPTED
                : activeIntent.owner().equals(intent.owner())
                ? Outcome.REPLACED
                : Outcome.PREEMPTED;
        activeIntent = intent;
        activeHandle = new Handle(intent.owner(), ++nextGeneration);
        return new Submission(outcome, activeHandle);
    }

    public boolean clear(Handle handle) {
        if (handle == null || !handle.equals(activeHandle)) {
            return false;
        }
        activeIntent = null;
        activeHandle = null;
        return true;
    }

    public boolean owns(Handle handle) {
        return handle != null && handle.equals(activeHandle);
    }

    public MovementIntent activeIntent() {
        return activeIntent;
    }

    public Handle activeHandle() {
        return activeHandle;
    }

    public void clear() {
        activeIntent = null;
        activeHandle = null;
    }
}
