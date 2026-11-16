package com.orangevillager61.emeraldcapitalism.navigation;

import java.util.Objects;

/**
 * Pure lifecycle of one job task. It owns the stage and the task generation and
 * nothing else; the behavior that owns the task performs the world effects.
 *
 * <p>A new generation begins with {@link #start()} so late cleanup from an old
 * task, which carries the old generation, cannot touch a newer task's claims.</p>
 */
public final class TaskLifecycle {

    private TaskStage stage = TaskStage.ACQUIRE;
    private long generation;
    private TaskInterruption lastInterruption;

    /** Begins a new task and returns its generation. Valid from any stage. */
    public long start() {
        stage = TaskStage.ACQUIRE;
        lastInterruption = null;
        return ++generation;
    }

    public TaskStage stage() {
        return stage;
    }

    public long generation() {
        return generation;
    }

    public TaskInterruption lastInterruption() {
        return lastInterruption;
    }

    /** Moves to {@code next}; returns false and changes nothing if the move is illegal. */
    public boolean advance(TaskStage next) {
        Objects.requireNonNull(next, "next");
        if (generation == 0L || !stage.canAdvanceTo(next)) {
            return false;
        }
        stage = next;
        return true;
    }

    /**
     * The walk ended. This moves a moving task on to its interaction and never to
     * {@link TaskStage#COMPLETE}: reaching a position is not finishing the job.
     */
    public boolean routeArrived() {
        return stage == TaskStage.MOVE && advance(TaskStage.INTERACT);
    }

    /** Completes the task. Only an interacting or continuing task can complete. */
    public boolean complete() {
        return (stage == TaskStage.INTERACT || stage == TaskStage.CONTINUE)
                && advance(TaskStage.COMPLETE);
    }

    /** Applies an interruption. Terminal tasks ignore it. */
    public boolean interrupt(TaskInterruption reason) {
        Objects.requireNonNull(reason, "reason");
        if (generation == 0L || stage.terminal()) {
            return false;
        }
        if (reason.resultingStage() == TaskStage.SUSPEND && stage == TaskStage.SUSPEND) {
            lastInterruption = reason;
            return true;
        }
        stage = reason.resultingStage();
        lastInterruption = reason;
        return true;
    }

    /** Resumes a suspended task by reacquiring: a new generation, back to ACQUIRE. */
    public long resume() {
        if (stage != TaskStage.SUSPEND) {
            throw new IllegalStateException("only a suspended task resumes, not " + stage);
        }
        return start();
    }
}
