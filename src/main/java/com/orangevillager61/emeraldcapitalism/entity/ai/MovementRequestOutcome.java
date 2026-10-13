package com.orangevillager61.emeraldcapitalism.entity.ai;

/** Result of submitting an intent to the per-entity movement owner. */
public enum MovementRequestOutcome {
    ACCEPTED,
    UNCHANGED,
    REPLACED,
    PREEMPTED,
    REJECTED_LOWER_PRIORITY,
    REJECTED_NOT_HIGHER_PRIORITY,
    REJECTED_STALE_HANDLE
}
