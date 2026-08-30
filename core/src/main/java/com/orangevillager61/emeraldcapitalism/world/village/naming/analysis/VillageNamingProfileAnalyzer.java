package com.orangevillager61.emeraldcapitalism.world.village.naming.analysis;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class VillageNamingProfileAnalyzer {

    public VillageNamingProfile analyze(VillageSignalSnapshot signals) {
        Objects.requireNonNull(signals, "signals");
        Map<VillageFeature, Double> featureScores = deriveFeatureScores(signals);
        Map<CivilizationalAxis, Double> axes = deriveCivilizationalAxes(featureScores, signals);

        Map<String, String> trace = new LinkedHashMap<>();
        trace.put("signal_count", Integer.toString(signals.all().size()));
        trace.put("strongest_feature", strongestFeature(featureScores));
        trace.put("strongest_axis", strongestAxis(axes).name());

        return new VillageNamingProfile(signals, featureScores, axes, trace);
    }

    private Map<VillageFeature, Double> deriveFeatureScores(VillageSignalSnapshot s) {
        Map<VillageFeature, Double> features = new LinkedHashMap<>();
        features.put(VillageFeature.SITE_WATER_ACCESS, clamp01(0.55 * s.value(VillageSignal.RIVER_ADJACENT) + 0.45 * s.value(VillageSignal.COAST_ADJACENT)));
        features.put(VillageFeature.SITE_EXPOSURE, clamp01(0.6 * s.value(VillageSignal.ELEVATION_EXPOSURE) + 0.4 * s.value(VillageSignal.LOCAL_HEIGHT_VARIATION)));
        features.put(VillageFeature.SITE_SHELTER, clamp01(0.75 * s.value(VillageSignal.BASIN_SHELTER) + 0.25 * (1.0 - s.value(VillageSignal.ELEVATION_EXPOSURE))));
        features.put(VillageFeature.AGRICULTURE, clamp01(weighted(
                s,
                VillageSignal.FARMLAND_COUNT, 0.45,
                VillageSignal.FARMER_POI_COUNT, 0.2,
                VillageSignal.COMPOSTER_COUNT, 0.1,
                VillageSignal.PLAINS, 0.15,
                VillageSignal.SETTLEMENT_SPREAD, 0.1
        ) * 0.85));
        features.put(VillageFeature.CRAFT, clamp01(weighted(s, VillageSignal.SMITHING_POI_COUNT, 0.55, VillageSignal.MASON_POI_COUNT, 0.2, VillageSignal.PRODUCTION_POI_DENSITY, 0.25)));
        features.put(VillageFeature.KNOWLEDGE, clamp01(weighted(s, VillageSignal.LECTERN_COUNT, 0.45, VillageSignal.BREWING_COUNT, 0.25, VillageSignal.CARTOGRAPHY_COUNT, 0.2, VillageSignal.KNOWLEDGE_POI_DENSITY, 0.1)));
        features.put(VillageFeature.SETTLEMENT_SCALE, clamp01(weighted(s, VillageSignal.VILLAGER_COUNT, 0.45, VillageSignal.BED_COUNT, 0.35, VillageSignal.HOUSING_COUNT, 0.2)));
        features.put(VillageFeature.SETTLEMENT_CENTER, clamp01(weighted(s, VillageSignal.BELL_CENTER_STRENGTH, 0.65, VillageSignal.PATH_CONNECTEDNESS, 0.35)));
        features.put(VillageFeature.LAYOUT_COMPACTNESS, clamp01(weighted(s, VillageSignal.LAYOUT_COMPACTNESS, 0.75, VillageSignal.SETTLEMENT_SPREAD, -0.35, VillageSignal.PATH_CONNECTEDNESS, 0.2)));
        features.put(VillageFeature.TRADE_CONTACT, clamp01(weighted(s, VillageSignal.ROUTE_CONNECTIVITY, 0.6, VillageSignal.USEFUL_STRUCTURE_NEARBY, 0.2, VillageSignal.RIVER_ADJACENT, 0.2)));
        features.put(VillageFeature.DANGER_PRESSURE, clamp01(weighted(s, VillageSignal.HOSTILE_STRUCTURE_NEARBY, 0.4, VillageSignal.DANGEROUS_RUIN_PROXIMITY, 0.35, VillageSignal.SWAMP, 0.15, VillageSignal.REMOTE_ISOLATED, 0.1)));
        features.put(VillageFeature.MEMORY_PRESSURE, clamp01(weighted(s, VillageSignal.MEMORY_STRUCTURE_PROXIMITY, 0.8, VillageSignal.DANGEROUS_RUIN_PROXIMITY, 0.2)));
        return features;
    }

    private Map<CivilizationalAxis, Double> deriveCivilizationalAxes(Map<VillageFeature, Double> f, VillageSignalSnapshot s) {
        EnumMap<CivilizationalAxis, Double> axes = new EnumMap<>(CivilizationalAxis.class);
        axes.put(CivilizationalAxis.PROSPERITY_EXCHANGE, clamp01(0.34 * f.get(VillageFeature.TRADE_CONTACT) + 0.24 * f.get(VillageFeature.SETTLEMENT_SCALE) + 0.2 * f.get(VillageFeature.SETTLEMENT_CENTER) + 0.22 * s.value(VillageSignal.BARREL_COUNT)));
        axes.put(CivilizationalAxis.PROTECTION_STRENGTH, clamp01(0.3 * s.value(VillageSignal.GOLEM_COUNT) + 0.3 * f.get(VillageFeature.LAYOUT_COMPACTNESS) + 0.2 * f.get(VillageFeature.SETTLEMENT_CENTER) + 0.2 * f.get(VillageFeature.SITE_EXPOSURE)));
        axes.put(CivilizationalAxis.DANGER_DECAY, clamp01(0.58 * f.get(VillageFeature.DANGER_PRESSURE) + 0.22 * s.value(VillageSignal.COAST_ADJACENT) + 0.2 * s.value(VillageSignal.REMOTE_ISOLATED)));
        axes.put(CivilizationalAxis.KNOWLEDGE_ENCHANTMENT, clamp01(0.65 * f.get(VillageFeature.KNOWLEDGE) + 0.2 * f.get(VillageFeature.SETTLEMENT_CENTER) + 0.15 * f.get(VillageFeature.MEMORY_PRESSURE)));
        axes.put(CivilizationalAxis.CRAFT_TRANSFORMATION, clamp01(0.72 * f.get(VillageFeature.CRAFT) + 0.18 * f.get(VillageFeature.LAYOUT_COMPACTNESS) + 0.1 * f.get(VillageFeature.SETTLEMENT_SCALE)));
        axes.put(CivilizationalAxis.SETTLEMENT_DWELLING, clamp01(0.45 * f.get(VillageFeature.SETTLEMENT_SCALE) + 0.35 * f.get(VillageFeature.SETTLEMENT_CENTER) + 0.2 * (1.0 - axes.get(CivilizationalAxis.DANGER_DECAY))));
        axes.put(CivilizationalAxis.GROWTH_FOOD, clamp01(
                0.45 * f.get(VillageFeature.AGRICULTURE)
                        + 0.2 * f.get(VillageFeature.SETTLEMENT_SCALE)
                        + 0.1 * s.value(VillageSignal.PLAINS)
                        + 0.25 * (1.0 - axes.get(CivilizationalAxis.DANGER_DECAY))
        ));
        axes.put(CivilizationalAxis.MEMORY_INHERITANCE, clamp01(0.78 * f.get(VillageFeature.MEMORY_PRESSURE) + 0.22 * s.value(VillageSignal.HOSTILE_STRUCTURE_NEARBY)));
        return axes;
    }

    private static double weighted(VillageSignalSnapshot signals,
                                   VillageSignal firstSignal, double firstWeight,
                                   VillageSignal secondSignal, double secondWeight) {
        return signals.value(firstSignal) * firstWeight
                + signals.value(secondSignal) * secondWeight;
    }

    private static double weighted(VillageSignalSnapshot signals,
                                   VillageSignal firstSignal, double firstWeight,
                                   VillageSignal secondSignal, double secondWeight,
                                   VillageSignal thirdSignal, double thirdWeight) {
        return weighted(signals, firstSignal, firstWeight, secondSignal, secondWeight)
                + signals.value(thirdSignal) * thirdWeight;
    }

    private static double weighted(VillageSignalSnapshot signals,
                                   VillageSignal firstSignal, double firstWeight,
                                   VillageSignal secondSignal, double secondWeight,
                                   VillageSignal thirdSignal, double thirdWeight,
                                   VillageSignal fourthSignal, double fourthWeight) {
        return weighted(signals, firstSignal, firstWeight, secondSignal, secondWeight,
                thirdSignal, thirdWeight) + signals.value(fourthSignal) * fourthWeight;
    }

    private static double weighted(VillageSignalSnapshot signals,
                                   VillageSignal firstSignal, double firstWeight,
                                   VillageSignal secondSignal, double secondWeight,
                                   VillageSignal thirdSignal, double thirdWeight,
                                   VillageSignal fourthSignal, double fourthWeight,
                                   VillageSignal fifthSignal, double fifthWeight) {
        return weighted(signals, firstSignal, firstWeight, secondSignal, secondWeight,
                thirdSignal, thirdWeight, fourthSignal, fourthWeight)
                + signals.value(fifthSignal) * fifthWeight;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String strongestFeature(Map<VillageFeature, Double> featureScores) {
        return featureScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey().id())
                .orElse("none");
    }

    private static CivilizationalAxis strongestAxis(Map<CivilizationalAxis, Double> axes) {
        return axes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(CivilizationalAxis.SETTLEMENT_DWELLING);
    }
}
