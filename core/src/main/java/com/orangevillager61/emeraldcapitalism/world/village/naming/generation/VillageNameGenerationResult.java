package com.orangevillager61.emeraldcapitalism.world.village.naming.generation;

import java.util.List;
import java.util.Optional;

public record VillageNameGenerationResult(
        List<NameCandidate> candidates,
        NameSelectionTrace trace,
        String failureReason
) {
    public VillageNameGenerationResult {
        candidates = List.copyOf(candidates);
    }

    public Optional<String> failureReasonOptional() {
        return Optional.ofNullable(failureReason);
    }
}
