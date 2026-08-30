package com.orangevillager61.emeraldcapitalism.world.village.naming.villager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable runtime view of {@code villager_names.json}.
 *
 * <p>The data file contains the naming system, not assembled names.  Keeping
 * this class independent of entities makes the phonology and drift pipeline
 * testable without loading a world.</p>
 */
public final class VillagerNamingData {

    public record BiomePool(List<String> first, List<String> second) {
        public BiomePool {
            first = List.copyOf(first);
            second = List.copyOf(second);
        }
    }

    public record Profession(String label, String root, Map<String, String> bynames,
                             Map<String, String> regionalRoots) {
        public Profession {
            bynames = Map.copyOf(bynames);
            regionalRoots = Map.copyOf(regionalRoots);
        }
    }

    public record DriftRule(String id, String operation) {
    }

    public record OriginParticle(String prefix, boolean enabled) {
        public OriginParticle {
            prefix = prefix == null ? "" : prefix.trim();
        }
    }

    /** Manually maintained exceptions that replace only slot 1. */
    public record SpecialFirstNames(List<String> names, double selectionChance) {
        public SpecialFirstNames {
            names = names == null ? List.of() : names.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (!Double.isFinite(selectionChance) || selectionChance < 0.0 || selectionChance > 1.0) {
                selectionChance = 0.0;
            }
        }
    }

    public record BiomeRule(String operation, String from, String to, String segments) {
    }

    public record DriftAssignment(double threshold, List<Integer> featureSizes,
                                  int minRules, int maxRules,
                                  List<Set<String>> inverseGroups,
                                  String ruleOrder) {
        public DriftAssignment {
            featureSizes = List.copyOf(featureSizes);
            inverseGroups = inverseGroups.stream()
                    .map(group -> Collections.unmodifiableSet(new LinkedHashSet<>(group)))
                    .toList();
            ruleOrder = ruleOrder == null ? "ascending_rule_index" : ruleOrder;
        }
    }

    private static final String VOWELS = "aeiou";
    private static final String STOPS = "bdgkpt";

    private final List<String> firstElements;
    private final List<String> secondElements;
    private final Map<String, BiomePool> pools;
    private final Map<String, BiomeRule> biomeRules;
    private final List<DriftRule> driftRules;
    private final Map<String, DriftRule> driftRulesById;
    private final DriftAssignment driftAssignment;
    private final Map<String, String> ageSuffixes;
    private final String nitwitSuffix;
    private final OriginParticle originParticle;
    private final OriginParticle mayorOriginParticle;
    private final SpecialFirstNames specialFirstNames;
    private final Map<String, Profession> professions;

    private VillagerNamingData(
            List<String> firstElements,
            List<String> secondElements,
            Map<String, BiomePool> pools,
            Map<String, BiomeRule> biomeRules,
            List<DriftRule> driftRules,
            DriftAssignment driftAssignment,
            Map<String, String> ageSuffixes,
            String nitwitSuffix,
            OriginParticle originParticle,
            OriginParticle mayorOriginParticle,
            SpecialFirstNames specialFirstNames,
            Map<String, Profession> professions
    ) {
        this.firstElements = List.copyOf(firstElements);
        this.secondElements = List.copyOf(secondElements);
        this.pools = Map.copyOf(pools);
        this.biomeRules = Map.copyOf(biomeRules);
        this.driftRules = List.copyOf(driftRules);
        this.driftRulesById = new LinkedHashMap<>();
        for (DriftRule rule : driftRules) {
            this.driftRulesById.put(rule.id(), rule);
        }
        this.driftAssignment = driftAssignment;
        this.ageSuffixes = Map.copyOf(ageSuffixes);
        this.nitwitSuffix = nitwitSuffix;
        this.originParticle = originParticle;
        this.mayorOriginParticle = mayorOriginParticle;
        this.specialFirstNames = specialFirstNames;
        this.professions = Map.copyOf(professions);
    }

    public static VillagerNamingData fromJson(JsonObject root) {
        JsonObject elements = object(root, "elements");
        List<String> first = strings(elements, "first");
        List<String> second = strings(elements, "second");
        if (first.isEmpty() || second.isEmpty()) {
            throw new IllegalArgumentException("Villager naming element sets may not be empty");
        }

        Map<String, BiomePool> pools = new LinkedHashMap<>();
        JsonObject poolObject = object(root, "pools");
        for (String biome : List.of("plains", "savanna", "desert", "taiga", "snowy")) {
            JsonObject pool = object(poolObject, biome);
            List<String> poolFirst = strings(pool, "first");
            List<String> poolSecond = strings(pool, "second");
            if (poolFirst.isEmpty() || poolSecond.isEmpty()) {
                throw new IllegalArgumentException("Villager naming biome pool may not be empty: " + biome);
            }
            pools.put(biome, new BiomePool(poolFirst, poolSecond));
        }

        Map<String, BiomeRule> biomeRules = new LinkedHashMap<>();
        JsonObject biomeRuleObject = object(root, "biome_rules");
        for (String biome : pools.keySet()) {
            JsonObject rule = object(biomeRuleObject, biome);
            biomeRules.put(biome, new BiomeRule(
                    string(rule, "operation"),
                    optionalString(rule, "from"),
                    optionalString(rule, "to"),
                    optionalString(rule, "segments")
            ));
        }

        List<DriftRule> driftRules = new ArrayList<>();
        for (JsonElement element : array(root, "drift_rules")) {
            JsonObject rule = element.getAsJsonObject();
            driftRules.add(new DriftRule(string(rule, "id"), string(rule, "operation")));
        }
        if (driftRules.size() != 12) {
            throw new IllegalArgumentException("Expected twelve villager drift rules");
        }

        JsonObject assignment = object(root, "drift_assignment");
        List<Integer> featureSizes = new ArrayList<>();
        for (JsonElement element : array(assignment, "feature_sizes")) {
            featureSizes.add(element.getAsInt());
        }
        List<Set<String>> inverseGroups = new ArrayList<>();
        for (JsonElement element : array(assignment, "inverse_groups")) {
            Set<String> group = new LinkedHashSet<>();
            for (JsonElement id : element.getAsJsonArray()) {
                group.add(id.getAsString());
            }
            inverseGroups.add(group);
        }
        DriftAssignment driftAssignment = new DriftAssignment(
                assignment.get("threshold").getAsDouble(),
                featureSizes,
                assignment.get("min_rules").getAsInt(),
                assignment.get("max_rules").getAsInt(),
                inverseGroups,
                string(assignment, "rule_order")
        );
        if (!Double.isFinite(driftAssignment.threshold())
                || driftAssignment.threshold() < 0.0 || driftAssignment.threshold() > 1.0
                || driftAssignment.minRules() < 1
                || driftAssignment.maxRules() < driftAssignment.minRules()
                || driftAssignment.maxRules() > driftRules.size()
                || !"ascending_rule_index".equals(driftAssignment.ruleOrder())
                || featureSizes.stream().anyMatch(size -> size <= 0)) {
            throw new IllegalArgumentException("Invalid villager drift assignment tuning");
        }
        if (featureSizes.size() != driftRules.size()) {
            throw new IllegalArgumentException("Drift feature-size count must match drift rules");
        }
        Set<String> driftRuleIds = driftRules.stream().map(DriftRule::id).collect(java.util.stream.Collectors.toSet());
        if (inverseGroups.stream().flatMap(Set::stream).anyMatch(id -> !driftRuleIds.contains(id))) {
            throw new IllegalArgumentException("Drift inverse group contains an unknown rule");
        }

        JsonObject suffixObject = object(root, "age_suffixes");
        Map<String, String> ageSuffixes = new LinkedHashMap<>();
        ageSuffixes.put("child", string(suffixObject, "child"));
        ageSuffixes.put("adult", string(suffixObject, "adult"));
        ageSuffixes.put("elder", string(suffixObject, "elder"));

        OriginParticle originParticle = parseOriginParticle(root, "origin_particle");
        OriginParticle mayorOriginParticle = parseOriginParticle(root, "mayor_origin_particle");

        SpecialFirstNames specialFirstNames = parseSpecialFirstNames(root);

        Map<String, Profession> professions = new LinkedHashMap<>();
        JsonObject professionObject = object(root, "professions");
        for (Map.Entry<String, JsonElement> entry : professionObject.entrySet()) {
            JsonObject profession = entry.getValue().getAsJsonObject();
            Map<String, String> bynames = new LinkedHashMap<>();
            JsonObject bynameObject = profession.getAsJsonObject("bynames");
            if (bynameObject != null) {
                for (Map.Entry<String, JsonElement> byname : bynameObject.entrySet()) {
                    bynames.put(byname.getKey(), byname.getValue().getAsString());
                }
            }
            Map<String, String> regionalRoots = new LinkedHashMap<>();
            JsonObject regionalRootObject = profession.getAsJsonObject("regional_roots");
            if (regionalRootObject != null) {
                for (Map.Entry<String, JsonElement> regionalRoot : regionalRootObject.entrySet()) {
                    regionalRoots.put(regionalRoot.getKey(), regionalRoot.getValue().getAsString());
                }
            }
            String rootValue = optionalString(profession, "root");
            professions.put(entry.getKey(), new Profession(
                    string(profession, "label"), rootValue, bynames, regionalRoots));
        }

        return new VillagerNamingData(
                first, second, pools, biomeRules, driftRules, driftAssignment,
                ageSuffixes, string(root, "nitwit_suffix"), originParticle, mayorOriginParticle,
                specialFirstNames, professions);
    }

    public List<String> firstElements() {
        return firstElements;
    }

    public List<String> secondElements() {
        return secondElements;
    }

    public BiomePool pool(String biome) {
        return pools.getOrDefault(normalizeBiome(biome), pools.get("plains"));
    }

    public DriftAssignment driftAssignment() {
        return driftAssignment;
    }

    public List<DriftRule> driftRules() {
        return driftRules;
    }

    public String ageSuffix(boolean child, boolean nitwit) {
        if (child || nitwit) {
            return ageSuffixes.get("child");
        }
        return ageSuffixes.get("adult");
    }

    /** Rule A1: vowel-initial suffixes take an inserted n after vowel-final stems. */
    public static String affix(String stem, String suffix) {
        if (stem == null || stem.isEmpty()) {
            return suffix == null ? "" : suffix.replaceFirst("^-", "");
        }
        String normalizedSuffix = suffix == null ? "" : suffix.replaceFirst("^-", "");
        return Set.of("a", "i", "o", "in", "ek", "ur").contains(normalizedSuffix)
                && endsInVowel(stem)
                ? stem + "n" + normalizedSuffix
                : stem + normalizedSuffix;
    }

    /** Segment-aware copy of root_converter.tokenize(). */
    public static List<String> tokenize(String word) {
        List<String> segments = new ArrayList<>();
        if (word == null) {
            return segments;
        }
        List<String> digraphs = List.of("ch", "sh", "th", "ng", "kw",
                "ei", "ih", "ii", "ai", "oo", "uu", "au", "oi");
        for (int index = 0; index < word.length();) {
            String matched = null;
            if (index + 1 < word.length()) {
                String candidate = word.substring(index, index + 2).toLowerCase(Locale.ROOT);
                if (digraphs.contains(candidate)) {
                    matched = candidate;
                }
            }
            if (matched == null) {
                matched = word.substring(index, index + 1).toLowerCase(Locale.ROOT);
            }
            segments.add(matched);
            index += matched.length();
        }
        return List.copyOf(segments);
    }

    public static boolean isVowel(String segment) {
        return segment != null && ("aeiou".contains(segment)
                || Set.of("ei", "ih", "ii", "ai", "oo", "uu", "au", "oi").contains(segment));
    }

    public static boolean endsInVowel(String stem) {
        List<String> segments = tokenize(stem);
        return !segments.isEmpty() && isVowel(segments.getLast());
    }

    /** Returns the configured elder suffix, if present. */
    public String elderSuffix() {
        return ageSuffixes.get("elder");
    }

    public OriginParticle originParticle() {
        return originParticle;
    }

    public SpecialFirstNames specialFirstNames() {
        return specialFirstNames;
    }

    /** Deterministically selects a rare manual exception once per villager. */
    public Optional<String> selectSpecialFirstName(long seed) {
        if (specialFirstNames.names().isEmpty() || specialFirstNames.selectionChance() <= 0.0) {
            return Optional.empty();
        }
        double roll = (mix(seed) >>> 11) * (1.0 / (1L << 53));
        if (roll >= specialFirstNames.selectionChance()) {
            return Optional.empty();
        }
        int index = (int) Math.floorMod(
                mix(seed ^ 0xD1B54A32D192ED03L), (long) specialFirstNames.names().size());
        return Optional.of(specialFirstNames.names().get(index));
    }

    /** Returns the configured origin slot, or an empty string until a village name exists. */
    public String originParticleText(String villageName) {
        if (!originParticle.enabled() || originParticle.prefix().isEmpty()
                || villageName == null || villageName.isBlank()) {
            return "";
        }
        return originParticle.prefix() + " " + villageName.trim();
    }

    /** Returns the mayor-only origin form; the generic origin particle is not rendered with it. */
    public String mayorOriginParticleText(String villageName) {
        if (!mayorOriginParticle.enabled() || mayorOriginParticle.prefix().isEmpty()
                || villageName == null || villageName.isBlank()) {
            return "";
        }
        return mayorOriginParticle.prefix() + villageName.trim();
    }

    private static OriginParticle parseOriginParticle(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonObject()) {
            return new OriginParticle("", false);
        }
        JsonObject object = element.getAsJsonObject();
        return new OriginParticle(
                optionalString(object, "prefix"),
                object.has("enabled") && object.get("enabled").getAsBoolean()
        );
    }

    private static SpecialFirstNames parseSpecialFirstNames(JsonObject root) {
        JsonElement element = root.get("special_first_names");
        if (element == null || !element.isJsonObject()) {
            return new SpecialFirstNames(List.of(), 0.0);
        }

        JsonObject object = element.getAsJsonObject();
        List<String> names = new ArrayList<>();
        JsonElement namesElement = object.get("names");
        if (namesElement != null && namesElement.isJsonArray()) {
            for (JsonElement name : namesElement.getAsJsonArray()) {
                if (name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
                    String value = name.getAsString().trim();
                    if (!value.isEmpty()) {
                        names.add(value);
                    }
                }
            }
        }

        double chance = 0.0;
        JsonElement chanceElement = object.get("selection_chance");
        if (chanceElement != null && chanceElement.isJsonPrimitive()
                && chanceElement.getAsJsonPrimitive().isNumber()) {
            chance = chanceElement.getAsDouble();
        }
        return new SpecialFirstNames(names, chance);
    }

    public String baseForm(String first, String second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return "";
        }
        char finalSegment = first.charAt(first.length() - 1);
        char initialSegment = second.charAt(0);
        if (STOPS.indexOf(finalSegment) >= 0 && VOWELS.indexOf(initialSegment) < 0) {
            if (finalSegment == initialSegment) {
                return first + second;
            }
            return first.substring(0, first.length() - 1) + second;
        }
        return first + second;
    }

    public String regionalForm(String base, String biome) {
        String normalizedBiome = normalizeBiome(biome);
        BiomeRule rule = biomeRules.getOrDefault(normalizedBiome,
                biomeRules.get("plains"));
        return switch (rule.operation()) {
            case "replace" -> base.replace(rule.from(), rule.to());
            case "simplify_geminates" -> simplifyGeminates(base);
            case "delete_final_stops" -> base.length() > 1
                    && rule.segments().indexOf(base.charAt(base.length() - 1)) >= 0
                    ? base.substring(0, base.length() - 1) : base;
            default -> base;
        };
    }

    public Profession profession(String key) {
        return professions.get(normalizeProfessionKey(key));
    }

    public String byname(String professionKey, String biome) {
        Profession profession = profession(professionKey);
        if (profession == null || profession.root() == null || profession.root().isBlank()) {
            return "";
        }
        String normalizedBiome = normalizeBiome(biome);
        String regionalRoot = profession.regionalRoots().getOrDefault(normalizedBiome,
                profession.regionalRoots().getOrDefault("plains", profession.root()));
        if (regionalRoot != null && !regionalRoot.isBlank()) {
            return affix(regionalRoot, "i");
        }
        return profession.bynames().getOrDefault(normalizedBiome,
                profession.bynames().getOrDefault("plains", ""));
    }

    /** Applies selected rules in workbook order, which is stable across saves. */
    public String applyDrift(List<String> selectedRuleIds, String value) {
        if (value == null || value.isEmpty() || selectedRuleIds == null || selectedRuleIds.isEmpty()) {
            return capitalize(value == null ? "" : value);
        }
        boolean titleCase = Character.isUpperCase(value.charAt(0));
        Set<String> selected = Set.copyOf(selectedRuleIds);
        String result = value.toLowerCase(Locale.ROOT);
        for (DriftRule rule : driftRules) {
            if (selected.contains(rule.id())) {
                result = applyOperation(rule.operation(), result);
            }
        }
        return titleCase ? capitalize(result) : result;
    }

    public static String normalizeBiome(String biome) {
        if (biome == null) {
            return "plains";
        }
        String normalized = biome.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "des", "desert" -> "desert";
            case "sav", "savanna" -> "savanna";
            case "tai", "taiga" -> "taiga";
            case "sno", "snowy", "snow" -> "snowy";
            default -> "plains";
        };
    }

    public static String normalizeProfessionKey(String key) {
        if (key == null) {
            return "none";
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        int namespace = normalized.lastIndexOf(':');
        if (namespace >= 0) {
            normalized = normalized.substring(namespace + 1);
        }
        int invalid = -1;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if ((current < 'a' || current > 'z') && (current < '0' || current > '9')) {
                invalid = index;
                break;
            }
        }
        if (invalid < 0) {
            return normalized;
        }
        StringBuilder result = new StringBuilder(normalized.length() - 1);
        result.append(normalized, 0, invalid);
        for (int index = invalid; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if ((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')) {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String applyOperation(String operation, String word) {
        if (word.isEmpty()) {
            return word;
        }
        return switch (operation) {
            case "drop_final_nasal" -> endsWithAny(word, "mn")
                    ? word.substring(0, word.length() - 1) : word;
            case "lenite_medial_s_to_h" -> replaceMedialSingle(word, 's', 'h');
            case "lower_i_to_e" -> word.replace('i', 'e');
            case "lower_u_to_o" -> word.replace('u', 'o');
            case "simplify_geminates" -> simplifyGeminates(word);
            case "drop_final_liquid" -> endsWithAny(word, "lr")
                    ? word.substring(0, word.length() - 1) : word;
            case "change_final_stop_to_nasal" -> changeFinalStopToNasal(word);
            case "lenite_medial_k_to_h" -> replaceMedialSingle(word, 'k', 'h');
            case "voice_initial_stop" -> replaceInitial(word, "ptk", "bdg");
            case "devoice_initial_stop" -> replaceInitial(word, "bdg", "ptk");
            case "lenite_medial_t_to_s" -> replaceMedialSingle(word, 't', 's');
            case "lenite_medial_m_to_w" -> replaceMedialSingle(word, 'm', 'w');
            default -> throw new IllegalArgumentException("Unknown drift operation: " + operation);
        };
    }

    private static String replaceMedialSingle(String word, char target, char replacement) {
        char[] chars = word.toCharArray();
        for (int index = 1; index < chars.length - 1; index++) {
            if (chars[index] == target && chars[index - 1] != target && chars[index + 1] != target) {
                chars[index] = replacement;
            }
        }
        return new String(chars);
    }

    private static String replaceInitial(String word, String from, String to) {
        int index = from.indexOf(word.charAt(0));
        return index < 0 ? word : to.charAt(index) + word.substring(1);
    }

    private static String changeFinalStopToNasal(String word) {
        char last = word.charAt(word.length() - 1);
        String replacement = switch (last) {
            case 'k', 'g' -> "ng";
            case 't', 'd' -> "n";
            case 'p', 'b' -> "m";
            default -> null;
        };
        return replacement == null ? word : word.substring(0, word.length() - 1) + replacement;
    }

    private static boolean endsWithAny(String value, String endings) {
        return endings.indexOf(value.charAt(value.length() - 1)) >= 0;
    }

    private static String simplifyGeminates(String value) {
        StringBuilder output = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (index > 0 && output.charAt(output.length() - 1) == current
                    && VOWELS.indexOf(current) < 0) {
                continue;
            }
            output.append(current);
        }
        return output.toString();
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("Missing villager naming object: " + key);
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("Missing villager naming array: " + key);
        }
        return element.getAsJsonArray();
    }

    private static List<String> strings(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array(object, key)) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing villager naming value: " + key);
        }
        return element.getAsString();
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
