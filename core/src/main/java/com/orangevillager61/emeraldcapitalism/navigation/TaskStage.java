package com.orangevillager61.emeraldcapitalism.navigation;

/**
 * The one vocabulary a villager job task uses to say where it is.
 *
 * <p>Arriving at an intermediate route position is {@link #MOVE} ending, not the
 * job ending: only {@link #COMPLETE} means the job's world effect happened.</p>
 */
public enum TaskStage {
    /** Choosing a target. Holds no claim. */
    ACQUIRE,
    /** Taking the claims the target needs, in the fixed claim order. */
    CLAIM,
    /** Walking toward the work position. Arrival does not complete the task. */
    MOVE,
    /** Performing the interaction at the work position. */
    INTERACT,
    /** More work remains after one interaction (next log, next batch item). */
    CONTINUE,
    /** The job's world effect happened. Terminal. */
    COMPLETE,
    /** Interrupted; the task will reacquire. Claims follow the interruption rule. */
    SUSPEND,
    /** Stopped on purpose, for example profession change. Terminal. */
    ABANDON,
    /** Could not finish, for example the target became unreachable. Terminal. */
    FAIL;

    public boolean terminal() {
        return this == COMPLETE || this == ABANDON || this == FAIL;
    }

    /** Whether a task may move directly from this stage to {@code next}. */
    public boolean canAdvanceTo(TaskStage next) {
        if (terminal()) {
            return false;
        }
        if (next == ABANDON || next == FAIL || next == SUSPEND) {
            return next != SUSPEND || this != SUSPEND;
        }
        return switch (this) {
            case ACQUIRE -> next == CLAIM;
            case CLAIM -> next == MOVE || next == INTERACT;
            case MOVE -> next == INTERACT;
            case INTERACT -> next == CONTINUE || next == COMPLETE;
            case CONTINUE -> next == MOVE || next == INTERACT || next == COMPLETE;
            case SUSPEND -> next == ACQUIRE;
            default -> false;
        };
    }
}
