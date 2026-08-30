package com.orangevillager61.emeraldcapitalism.world.village.naming.generation;

import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.CivilizationalAxis;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageNamingProfile;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignal;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageFeature;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.ConceptRoot;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootLexicon;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootSection;

import java.util.*;

public final class VillageNameGenerator {

    public VillageNameGenerationResult generate(VillageNamingProfile profile, RootLexicon lexicon, long seed, int limit) {
        Map<String, Double> boostedSections = computeSectionWeights(profile);
        Map<String, Double> consideredRoots = new LinkedHashMap<>();
        List<String> decisionLog = new ArrayList<>();

        List<ScoredRoot> scoredRoots = new ArrayList<>();
        for (Map.Entry<String, Double> sectionBoost : boostedSections.entrySet()) {
            List<ConceptRoot> sectionRoots = lexicon.rootsBySection(sectionBoost.getKey());
            double sectionHintTotal = sectionRoots.stream()
                    .mapToDouble(root -> Math.max(0.0, root.weightHint()))
                    .sum();
            for (ConceptRoot root : sectionRoots) {
                double score = (sectionBoost.getValue() * root.weightHint()) + deterministicTieBreak(seed, root.root());
                double selectionWeight = sectionHintTotal > 0.0
                        ? Math.max(0.0, sectionBoost.getValue()) * Math.max(0.0, root.weightHint()) / sectionHintTotal
                        : 0.0;
                consideredRoots.put(root.root(), score);
                scoredRoots.add(new ScoredRoot(root, score, selectionWeight));
            }
        }

        if (scoredRoots.isEmpty()) {
            decisionLog.add("No compatible canonical roots matched boosted sections");
            return new VillageNameGenerationResult(
                    List.of(),
                    new NameSelectionTrace(boostedSections, consideredRoots, decisionLog),
                    "No compatible canonical root set available"
            );
        }

        int pairLimit = Math.min(limit, Math.max(1, scoredRoots.size()));
        Random random = new Random(seed);
        List<NameCandidate> candidates = new ArrayList<>();
        Set<String> usedPrimaryRoots = new HashSet<>();
        String dialect = detectDialect(profile);
        int rerollCount = 0;

        for (int i = 0; i < pairLimit; i++) {
            ScoredRoot primary = pickWeightedRoot(scoredRoots, random, usedPrimaryRoots, null);
            usedPrimaryRoots.add(primary.root().root());
            NameCandidate accepted = null;
            int attempts = Math.max(16, scoredRoots.size() * 2);
            for (int attempt = 0; attempt < attempts; attempt++) {
                ScoredRoot secondary = pickWeightedRoot(
                        scoredRoots, random, Set.of(primary.root().root()), primary.root().section());
                String primaryForm = primary.root().formForDialect(dialect);
                String secondaryForm = secondary.root().formForDialect(dialect);
                String joined = joinPlaceName(primaryForm, secondaryForm, primary.root().stratum());
                if (!isLegalPlaceName(joined)) {
                    rerollCount++;
                    decisionLog.add("reroll illegal=" + joined + " primary=" + primary.root().root()
                            + " secondary=" + secondary.root().root());
                    continue;
                }
                String rendered = compose(joined, "");
                double score = (primary.score() * 0.62) + (secondary.score() * 0.38);
                accepted = new NameCandidate(rendered,
                        List.of(primary.root().root(), secondary.root().root()),
                        score, Math.min(1.0, score));
                decisionLog.add("candidate=" + rendered + " dialect="
                        + (dialect == null ? "standard" : dialect)
                        + " primary=" + primary.root().root() + " secondary=" + secondary.root().root());
                break;
            }
            if (accepted != null) {
                candidates.add(accepted);
            } else {
                decisionLog.add("No legal place-name pair remained for primary=" + primary.root().root());
            }
        }

        candidates.sort(Comparator.comparingDouble(NameCandidate::score).reversed().thenComparing(NameCandidate::renderedName));
        String failure = candidates.isEmpty() ? "No phonotactically legal canonical place-name pair available" : null;
        return new VillageNameGenerationResult(
                candidates,
                new NameSelectionTrace(boostedSections, consideredRoots, decisionLog, rerollCount),
                failure);
    }

    private Map<String, Double> computeSectionWeights(VillageNamingProfile profile) {
        Map<String, Double> weights = new LinkedHashMap<>();

        profile.civilizationalScores().entrySet().stream()
                .sorted(Map.Entry.<CivilizationalAxis, Double>comparingByValue().reversed())
                .limit(4)
                .forEach(entry -> mapAxisToSections(entry.getKey()).forEach(section ->
                        weights.merge(section.id(), entry.getValue(), Double::sum)));

        mergeFeature(weights, VillageFeature.AGRICULTURE, RootSection.GROWTH_FOOD, profile);
        mergeFeature(weights, VillageFeature.CRAFT, RootSection.CRAFT_TRANSFORMATION, profile);
        mergeFeature(weights, VillageFeature.KNOWLEDGE, RootSection.KNOWLEDGE_ENCHANTMENT, profile);
        mergeFeature(weights, VillageFeature.DANGER_PRESSURE, RootSection.DANGER_DECAY, profile);
        mergeFeature(weights, VillageFeature.SETTLEMENT_CENTER, RootSection.SETTLEMENT_DWELLING, profile);
        mergeFeature(weights, VillageFeature.LAYOUT_COMPACTNESS, RootSection.LAYOUT_DENSITY_SCALE, profile);
        mergeFeature(weights, VillageFeature.TRADE_CONTACT, RootSection.CONNECTION_TRANSIT, profile);
        mergeFeature(weights, VillageFeature.SITE_WATER_ACCESS, RootSection.COAST_FISHING_WATER_TRAVEL, profile);

        return weights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(8)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }

    private List<RootSection> mapAxisToSections(CivilizationalAxis axis) {
        return switch (axis) {
            case PROSPERITY_EXCHANGE -> List.of(
                    RootSection.PROSPERITY_EXCHANGE,
                    RootSection.BANK_EXCHANGE_INSTITUTIONAL,
                    RootSection.QUANTITY_SUFFICIENCY,
                    RootSection.CONNECTION_TRANSIT,
                    RootSection.TERRAIN_ROUTE_COAST
            );
            case PROTECTION_STRENGTH -> List.of(RootSection.PROTECTION_STRENGTH);
            case DANGER_DECAY -> List.of(RootSection.DANGER_DECAY);
            case KNOWLEDGE_ENCHANTMENT -> List.of(
                    RootSection.KNOWLEDGE_ENCHANTMENT,
                    RootSection.MENTAL_CAPACITY,
                    RootSection.CONCEPTS,
                    RootSection.SPEECH_AND_UNDERSTANDING
            );
            case CRAFT_TRANSFORMATION -> List.of(
                    RootSection.CRAFT_TRANSFORMATION,
                    RootSection.BUILDINGS_WORKSTATIONS,
                    RootSection.PROFESSION_VOCABULARY
            );
            case SETTLEMENT_DWELLING -> List.of(
                    RootSection.SETTLEMENT_DWELLING,
                    RootSection.BODY_AND_PERSON,
                    RootSection.LAYOUT_DENSITY_SCALE,
                    RootSection.SHELTERED_ENCLOSED_TERRAIN
            );
            case GROWTH_FOOD -> List.of(
                    RootSection.GROWTH_FOOD,
                    RootSection.BIOME_MATERIAL_IDENTITY,
                    RootSection.COAST_FISHING_WATER_TRAVEL
            );
            case MEMORY_INHERITANCE -> List.of(
                    RootSection.MEMORY_INHERITANCE,
                    RootSection.TIME,
                    RootSection.THE_BUILDERS,
                    RootSection.NEARBY_STRUCTURES_ADJACENCY
            );
        };
    }

    private static String detectDialect(VillageNamingProfile profile) {
        if (profile.rawSignals().value(VillageSignal.DESERT) > 0.0) {
            return "desert";
        }
        if (profile.rawSignals().value(VillageSignal.SAVANNA) > 0.0) {
            return "savanna";
        }
        if (profile.rawSignals().value(VillageSignal.SNOWY) > 0.0) {
            return "snowy";
        }
        if (profile.rawSignals().value(VillageSignal.TAIGA) > 0.0) {
            return "taiga";
        }
        return null;
    }

    private static void mergeFeature(Map<String, Double> weights, VillageFeature featureKey,
                                     RootSection section, VillageNamingProfile profile) {
        double feature = profile.featureScores().getOrDefault(featureKey, 0.0);
        if (feature > 0.0) {
            double multiplier = featureKey == VillageFeature.AGRICULTURE ? 0.35 : 0.65;
            weights.merge(section.id(), feature * multiplier, Double::sum);
        }
    }

    /** C1 for place names: preserve both roots and apply seam repairs without the clip. */
    public static String joinPlaceName(String modifier, String head, String modifierStratum) {
        List<String> modifierSegments = tokenize(modifier);
        List<String> headSegments = tokenize(head);
        if (modifierSegments.isEmpty() || headSegments.isEmpty()) {
            return modifier + head;
        }

        if (isVowel(modifierSegments.getLast()) && isVowel(headSegments.getFirst())) {
            modifierSegments.removeLast();
        }
        if (!modifierSegments.isEmpty() && !headSegments.isEmpty()
                && !isVowel(modifierSegments.getLast())
                && modifierSegments.getLast().equals(headSegments.getFirst())) {
            modifierSegments.removeLast();
        }
        if ("5".equals(modifierStratum)
                && !modifierSegments.isEmpty()
                && !headSegments.isEmpty()
                && STOPS.contains(modifierSegments.getLast())
                && !isVowel(headSegments.getFirst())) {
            modifierSegments.removeLast();
        }

        List<String> onset = onsetOf(headSegments);
        while (!modifierSegments.isEmpty()) {
            List<String> coda = codaOf(modifierSegments);
            if (coda.isEmpty()
                    || (legalCoda(coda) && coda.size() + onset.size() <= 3)) {
                break;
            }
            modifierSegments.removeLast();
        }
        List<String> joined = new ArrayList<>(modifierSegments.size() + headSegments.size());
        joined.addAll(modifierSegments);
        joined.addAll(headSegments);
        return untokenize(joined);
    }

    private static String compose(String first, String second) {
        String combined = Objects.requireNonNull(first, "first")
                + Objects.requireNonNull(second, "second");
        return Character.toUpperCase(combined.charAt(0)) + combined.substring(1);
    }

    private static final Set<String> STOPS = Set.of("b", "d", "g", "k", "p", "t");
    private static final Set<String> VOWELS = Set.of("a", "e", "i", "o", "u",
            "ei", "ih", "ii", "ai", "oo", "uu", "au", "oi");
    private static final Set<String> CONSONANTS = Set.of(
            "b", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "r", "s", "t", "v", "w", "z",
            "ch", "sh", "th", "ng", "kw");
    private static final Set<String> LEGAL_CODA_2 = Set.of(
            "rp", "rt", "rd", "rl", "rn", "rm", "lt", "ld", "lk", "lm", "ng", "nt", "nd", "mb", "mf",
            "st", "sk", "kt", "nth", "tch", "sh", "th", "ch", "lf", "rk", "rf", "nk", "mp", "sp", "ft",
            "wl", "wr");

    private static List<String> tokenize(String word) {
        List<String> segments = new ArrayList<>();
        List<String> digraphs = List.of("ch", "sh", "th", "ng", "kw",
                "ei", "ih", "ii", "ai", "oo", "uu", "au", "oi");
        for (int index = 0; index < word.length();) {
            String candidate = index + 1 < word.length()
                    ? word.substring(index, index + 2).toLowerCase(Locale.ROOT) : "";
            if (digraphs.contains(candidate)) {
                segments.add(candidate);
                index += 2;
            } else {
                segments.add(word.substring(index, index + 1).toLowerCase(Locale.ROOT));
                index++;
            }
        }
        return segments;
    }

    private static String untokenize(List<String> segments) {
        return String.join("", segments);
    }

    private static boolean isVowel(String segment) {
        return VOWELS.contains(segment);
    }

    private static List<String> onsetOf(List<String> segments) {
        List<String> onset = new ArrayList<>();
        for (String segment : segments) {
            if (isVowel(segment)) {
                break;
            }
            onset.add(segment);
        }
        return onset;
    }

    private static List<String> codaOf(List<String> segments) {
        List<String> coda = new ArrayList<>();
        for (int index = segments.size() - 1; index >= 0 && !isVowel(segments.get(index)); index--) {
            coda.add(0, segments.get(index));
        }
        return coda;
    }

    private static boolean legalCoda(List<String> coda) {
        return coda.size() <= 1 || LEGAL_CODA_2.contains(untokenize(coda));
    }

    private static boolean legalOnset(List<String> onset) {
        if (onset.size() <= 1) {
            return true;
        }
        if (onset.getFirst().equals("s")) {
            return onset.size() == 2 || (onset.size() == 3
                    && STOPS.contains(onset.get(1))
                    && Set.of("l", "r", "w", "y").contains(onset.get(2)));
        }
        if (onset.size() == 2 && onset.getFirst().equals("w")
                && Set.of("l", "r").contains(onset.get(1))) {
            return true;
        }
        return onset.size() == 2
                && Set.of("b", "d", "f", "g", "k", "p", "s", "t", "v", "z", "ch", "sh", "th", "kw")
                .contains(onset.getFirst())
                && Set.of("l", "r", "w", "y").contains(onset.get(1));
    }

    private static boolean isLegalPlaceName(String word) {
        List<String> segments = tokenize(word);
        if (segments.isEmpty() || segments.stream().noneMatch(VillageNameGenerator::isVowel)) {
            return false;
        }
        if (segments.size() > 1 && segments.stream().distinct().count() == 1) {
            return false;
        }
        if (segments.stream().anyMatch(segment -> !VOWELS.contains(segment) && !CONSONANTS.contains(segment))) {
            return false;
        }
        List<String> onset = onsetOf(segments);
        List<String> coda = codaOf(segments);
        return onset.size() <= 3 && legalOnset(onset) && coda.size() <= 2 && legalCoda(coda);
    }

    private static double deterministicTieBreak(long seed, String root) {
        long mixed = seed ^ (31L * root.hashCode());
        mixed ^= (mixed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= (mixed >>> 33);
        long bucket = Math.floorMod(mixed, 1000L);
        return bucket / 100_000.0; // 0.00000 .. 0.00999
    }

    private static ScoredRoot pickWeightedRoot(
            List<ScoredRoot> roots,
            Random random,
            Set<String> excludedRoots,
            String excludedSection
    ) {
        List<ScoredRoot> eligible = roots.stream()
                .filter(root -> !excludedRoots.contains(root.root().root()))
                .filter(root -> excludedSection == null || !excludedSection.equals(root.root().section()))
                .toList();

        if (eligible.isEmpty() && excludedSection != null) {
            eligible = roots.stream()
                    .filter(root -> !excludedRoots.contains(root.root().root()))
                    .toList();
        }
        if (eligible.isEmpty()) {
            eligible = roots;
        }

        double totalWeight = eligible.stream().mapToDouble(ScoredRoot::selectionWeight).sum();
        if (totalWeight <= 0.0) {
            return eligible.get(random.nextInt(eligible.size()));
        }

        double target = random.nextDouble() * totalWeight;
        for (ScoredRoot root : eligible) {
            target -= root.selectionWeight();
            if (target <= 0.0) {
                return root;
            }
        }
        return eligible.getLast();
    }

    private record ScoredRoot(ConceptRoot root, double score, double selectionWeight) {
    }
}
