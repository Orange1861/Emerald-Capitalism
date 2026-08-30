package com.orangevillager61.emeraldcapitalism.world.village.naming.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record VillageNamingProfile(
        VillageSignalSnapshot rawSignals,
        Map<VillageFeature, Double> featureScores,
        Map<CivilizationalAxis, Double> civilizationalScores,
        Map<String, String> traceMetadata
) {
    public VillageNamingProfile {
        rawSignals = Objects.requireNonNull(rawSignals, "rawSignals");
        featureScores = Collections.unmodifiableMap(new LinkedHashMap<>(featureScores));
        EnumMap<CivilizationalAxis, Double> axisCopy = new EnumMap<>(CivilizationalAxis.class);
        axisCopy.putAll(civilizationalScores);
        civilizationalScores = Collections.unmodifiableMap(axisCopy);
        traceMetadata = Collections.unmodifiableMap(new LinkedHashMap<>(traceMetadata));
    }

    public String debugSummary() {
        String featureSummary = featureScores.entrySet().stream()
                .sorted(Map.Entry.<VillageFeature, Double>comparingByValue().reversed())
                .map(entry -> entry.getKey().id() + "=" + String.format("%.3f", entry.getValue()))
                .collect(Collectors.joining(", "));

        String axisSummary = civilizationalScores.entrySet().stream()
                .sorted(Map.Entry.<CivilizationalAxis, Double>comparingByValue().reversed())
                .map(entry -> entry.getKey().name().toLowerCase() + "=" + String.format("%.3f", entry.getValue()))
                .collect(Collectors.joining(", "));

        return "features[" + featureSummary + "] axes[" + axisSummary + "]";
    }
}
