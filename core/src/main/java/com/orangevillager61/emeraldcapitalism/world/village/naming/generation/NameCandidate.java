package com.orangevillager61.emeraldcapitalism.world.village.naming.generation;

import java.util.List;

public record NameCandidate(
        String renderedName,
        List<String> rootParts,
        double score,
        double confidence
) {
    public NameCandidate {
        rootParts = List.copyOf(rootParts);
    }
}
