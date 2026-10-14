package com.orangevillager61.emeraldcapitalism.entity.ai;

/** Explicit outcome reported when a movement request stops owning execution. */
public enum MovementRequestTerminationReason {
    ARRIVED,
    ABANDONED,
    FAILED,
    PREEMPTED
}
