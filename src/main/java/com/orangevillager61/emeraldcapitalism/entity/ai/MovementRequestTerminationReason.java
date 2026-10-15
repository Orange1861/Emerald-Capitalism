package com.orangevillager61.emeraldcapitalism.entity.ai;

/** Explicit outcome reported when a movement request stops owning execution. */
public enum MovementRequestTerminationReason {
    ARRIVED,
    ABANDONED,
    FAILED,
    PREEMPTED,
    /** A same-owner generation was superseded by a newer request. */
    REPLACED,
    /** The entity/chunk session ended before the task could report normally. */
    UNLOADED,
    /** The server stopped while the request was still active. */
    SERVER_SHUTDOWN
}
