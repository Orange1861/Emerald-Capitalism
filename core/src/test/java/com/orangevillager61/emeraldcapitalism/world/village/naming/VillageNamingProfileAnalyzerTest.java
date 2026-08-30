package com.orangevillager61.emeraldcapitalism.world.village.naming;

import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.CivilizationalAxis;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageFeature;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageNamingProfile;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageNamingProfileAnalyzer;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignal;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignalSnapshot;
import com.orangevillager61.emeraldcapitalism.world.village.naming.generation.NameSelectionTrace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageNamingProfileAnalyzerTest {

    @Test
    void typedFeatureScoresPreserveWeightedValues() {
        VillageSignalSnapshot signals = VillageSignalSnapshot.builder()
                .with(VillageSignal.FARMLAND_COUNT, 1.0)
                .with(VillageSignal.FARMER_POI_COUNT, 0.8)
                .with(VillageSignal.COMPOSTER_COUNT, 0.7)
                .with(VillageSignal.PLAINS, 1.0)
                .build();

        VillageNamingProfile profile = new VillageNamingProfileAnalyzer().analyze(signals);

        assertEquals(0.7055, profile.featureScores().get(VillageFeature.AGRICULTURE), 0.000001);
        assertTrue(profile.featureScores().keySet().stream().allMatch(VillageFeature.class::isInstance));
        assertThrows(UnsupportedOperationException.class,
                () -> profile.featureScores().put(VillageFeature.CRAFT, 1.0));
    }

    @Test
    void zeroAndPartialSnapshotsRetainFallbacksAndClamps() {
        VillageNamingProfile zero = new VillageNamingProfileAnalyzer().analyze(
                VillageSignalSnapshot.builder().build());
        assertEquals("site_shelter", zero.traceMetadata().get("strongest_feature"));
        assertEquals("GROWTH_FOOD", zero.traceMetadata().get("strongest_axis"));

        VillageNamingProfile partial = new VillageNamingProfileAnalyzer().analyze(
                VillageSignalSnapshot.builder()
                        .with(VillageSignal.ELEVATION_EXPOSURE, -10.0)
                        .with(VillageSignal.LOCAL_HEIGHT_VARIATION, 10.0)
                        .with(VillageSignal.ROUTE_CONNECTIVITY, 10.0)
                        .with(VillageSignal.SETTLEMENT_SPREAD, -10.0)
                        .build());
        assertTrue(partial.featureScores().values().stream().allMatch(value -> value >= 0.0 && value <= 1.0));
        assertTrue(partial.civilizationalScores().values().stream()
                .allMatch(value -> value >= 0.0 && value <= 1.0));
    }

    @Test
    void profileCopiesInputMaps() {
        Map<VillageFeature, Double> features = new java.util.LinkedHashMap<>();
        Map<CivilizationalAxis, Double> axes = new java.util.EnumMap<>(CivilizationalAxis.class);
        Map<String, String> trace = new java.util.LinkedHashMap<>();
        VillageNamingProfile profile = new VillageNamingProfile(
                VillageSignalSnapshot.builder().build(), features, axes, trace);

        features.put(VillageFeature.CRAFT, 1.0);
        axes.put(CivilizationalAxis.CRAFT_TRANSFORMATION, 1.0);
        trace.put("changed", "after construction");

        assertTrue(profile.featureScores().isEmpty());
        assertTrue(profile.civilizationalScores().isEmpty());
        assertTrue(profile.traceMetadata().isEmpty());
    }

    @Test
    void selectionTraceCopiesAndFreezesItsInputs() {
        Map<String, Double> boostedSections = new LinkedHashMap<>();
        Map<String, Double> consideredRoots = new LinkedHashMap<>();
        java.util.List<String> decisionLog = new java.util.ArrayList<>();
        NameSelectionTrace trace = new NameSelectionTrace(boostedSections, consideredRoots, decisionLog);

        boostedSections.put("growth_food", 1.0);
        consideredRoots.put("emra", 1.0);
        decisionLog.add("changed");

        assertTrue(trace.boostedSections().isEmpty());
        assertTrue(trace.consideredRoots().isEmpty());
        assertTrue(trace.decisionLog().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> trace.boostedSections().put("growth_food", 1.0));
    }
}
