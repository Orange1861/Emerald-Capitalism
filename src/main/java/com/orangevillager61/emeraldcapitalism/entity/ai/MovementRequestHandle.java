package com.orangevillager61.emeraldcapitalism.entity.ai;

import java.util.Objects;

/** Capability token returned to a behavior that owns a movement request. */
public record MovementRequestHandle(String owner, long generation) {
    public MovementRequestHandle {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }
}
