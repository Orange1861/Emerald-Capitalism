package com.orangevillager61.emeraldcapitalism.world.village.naming.generation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record NameSelectionTrace(
        Map<String, Double> boostedSections,
        Map<String, Double> consideredRoots,
        List<String> decisionLog,
        int rerollCount
) {
    public NameSelectionTrace(Map<String, Double> boostedSections,
                              Map<String, Double> consideredRoots,
                              List<String> decisionLog) {
        this(boostedSections, consideredRoots, decisionLog, 0);
    }

    public NameSelectionTrace {
        boostedSections = Collections.unmodifiableMap(new LinkedHashMap<>(boostedSections));
        consideredRoots = Collections.unmodifiableMap(new LinkedHashMap<>(consideredRoots));
        decisionLog = List.copyOf(decisionLog);
        if (rerollCount < 0) {
            throw new IllegalArgumentException("rerollCount cannot be negative");
        }
    }

    public String debugSummary() {
        return String.join(" | ", decisionLog);
    }
}
