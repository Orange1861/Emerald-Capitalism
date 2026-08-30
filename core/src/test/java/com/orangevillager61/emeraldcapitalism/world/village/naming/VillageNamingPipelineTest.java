package com.orangevillager61.emeraldcapitalism.world.village.naming;

import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.*;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootLexicon;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootLexiconParser;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootSection;
import com.orangevillager61.emeraldcapitalism.world.village.naming.generation.VillageNameGenerationResult;
import com.orangevillager61.emeraldcapitalism.world.village.naming.generation.VillageNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class VillageNamingPipelineTest {

    @Test
    void loadsCanonicalRootsAndIndexesBySection() {
        RootLexicon lexicon = RootLexiconParser.parse(new StringReader("""
                {
                  "roots": [
                    {"root":"emra","section":"prosperity_exchange","meaning":"wealth","origin":"emerald"},
                    {"root":"vak","section":"protection_strength_communal_defense","meaning":"watch","origin":"bell"},
                    {"root":"vak","section":"protection_strength_communal_defense","meaning":"watch","origin":"bell"}
                  ]
                }
                """));

        assertEquals(2, lexicon.roots().size());
        assertEquals(1, lexicon.rootsBySection("prosperity_exchange").size());
    }

    @Test
    void rejectsConflictingDuplicateRoots() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RootLexiconParser.parse(new StringReader("""
                        {
                          "roots": [
                            {"root":"emra","section":"prosperity_exchange","meaning":"wealth","origin":"emerald"},
                            {"root":"emra","section":"growth_food_worked_land","meaning":"field","origin":"farm"}
                          ]
                        }
                        """)));

        assertTrue(ex.getMessage().contains("Conflicting duplicate canonical root"));
    }

    @Test
    void loadsCanonicalRootCountsAndMapsEveryEnabledSection() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/emeraldcapitalism/village_naming/roots.json")) {
            assertNotNull(stream);
            RootLexicon lexicon = RootLexiconParser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));

            assertEquals(231, lexicon.roots().size());
            assertEquals(209, lexicon.enabledRoots().size());

            Set<String> mappedSections = Arrays.stream(RootSection.values())
                    .map(RootSection::id)
                    .collect(Collectors.toSet());
            assertEquals(mappedSections, lexicon.sections());
        }
    }

    @Test
    void derivesCivilizationalScoresFromSignals() {
        VillageSignalSnapshot signals = VillageSignalSnapshot.builder()
                .with(VillageSignal.FARMLAND_COUNT, 1.0)
                .with(VillageSignal.FARMER_POI_COUNT, 0.8)
                .with(VillageSignal.COMPOSTER_COUNT, 0.7)
                .with(VillageSignal.BELL_CENTER_STRENGTH, 0.9)
                .with(VillageSignal.VILLAGER_COUNT, 0.7)
                .with(VillageSignal.BED_COUNT, 0.8)
                .with(VillageSignal.PLAINS, 1.0)
                .build();

        VillageNamingProfile profile = new VillageNamingProfileAnalyzer().analyze(signals);

        assertTrue(profile.featureScores().get(VillageFeature.AGRICULTURE) > 0.7);
        assertTrue(profile.civilizationalScores().get(CivilizationalAxis.GROWTH_FOOD) > 0.7);
        assertTrue(profile.civilizationalScores().get(CivilizationalAxis.SETTLEMENT_DWELLING) > 0.5);
    }

    @Test
    void generatesDeterministicCandidatesWithFixedSeed() {
        RootLexicon lexicon = RootLexiconParser.parse(new StringReader("""
                {
                  "roots": [
                    {"root":"emra","section":"prosperity_exchange","meaning":"wealth","origin":"emerald"},
                    {"root":"selm","section":"prosperity_exchange","meaning":"trade","origin":"menu"},
                    {"root":"vak","section":"protection_strength_communal_defense","meaning":"watch","origin":"bell"},
                    {"root":"tara","section":"connection_transit","meaning":"route","origin":"paths"}
                  ]
                }
                """));

        VillageSignalSnapshot signals = VillageSignalSnapshot.builder()
                .with(VillageSignal.ROUTE_CONNECTIVITY, 0.9)
                .with(VillageSignal.BELL_CENTER_STRENGTH, 0.8)
                .with(VillageSignal.VILLAGER_COUNT, 0.7)
                .with(VillageSignal.BARREL_COUNT, 0.6)
                .build();

        VillageNamingProfile profile = new VillageNamingProfileAnalyzer().analyze(signals);
        VillageNameGenerator generator = new VillageNameGenerator();

        VillageNameGenerationResult first = generator.generate(profile, lexicon, 1234L, 3);
        VillageNameGenerationResult second = generator.generate(profile, lexicon, 1234L, 3);

        assertEquals(first.candidates(), second.candidates());
        assertEquals(first.trace(), second.trace());
        assertFalse(first.candidates().isEmpty());
    }

    @Test
    void usesC1SeamRepairsWithoutThePlaceNameClip() {
        assertEquals("emrahah", VillageNameGenerator.joinPlaceName("emra", "hah", "5"));
        assertEquals("polapakomp", VillageNameGenerator.joinPlaceName("polapa", "komp", "5"));
        assertEquals("shipwrekrist", VillageNameGenerator.joinPlaceName("shipwrek", "krist", "5"));
        assertNotEquals("polkomp", VillageNameGenerator.joinPlaceName("polapa", "komp", "5"));
    }

    @Test
    void addsReachableSectionsAtTheSameAxisWeight() {
        RootLexicon lexicon = RootLexiconParser.parse(new StringReader("""
                {
                  "roots": [
                    {"root":"pro","section":"prosperity_exchange","meaning":"trade"},
                    {"root":"bank","section":"bank_exchange_institutional","meaning":"wealth"},
                    {"root":"many","section":"quantity_sufficiency","meaning":"plenty"}
                  ]
                }
                """));
        VillageNamingProfile profile = profileFor(VillageSignalSnapshot.builder().build(), CivilizationalAxis.PROSPERITY_EXCHANGE);

        VillageNameGenerationResult result = new VillageNameGenerator().generate(profile, lexicon, 7L, 1);

        assertEquals(1.0, result.trace().boostedSections().get("prosperity_exchange"));
        assertEquals(1.0, result.trace().boostedSections().get("bank_exchange_institutional"));
        assertEquals(1.0, result.trace().boostedSections().get("quantity_sufficiency"));
    }

    @Test
    void resolvesDialectFormsBeforeJoiningAndKeepsCanonicalRootTrace() {
        RootLexicon lexicon = RootLexiconParser.parse(new StringReader("""
                {
                  "roots": [
                    {
                      "root":"standarda",
                      "section":"prosperity_exchange",
                      "meaning":"trade",
                      "dialects":{"desert":"deserta"}
                    },
                    {
                      "root":"standardb",
                      "section":"bank_exchange_institutional",
                      "meaning":"wealth",
                      "dialects":{"desert":"desertb"}
                    }
                  ]
                }
                """));
        VillageNameGenerator generator = new VillageNameGenerator();
        VillageNamingProfile standardProfile = profileFor(VillageSignalSnapshot.builder().build(), CivilizationalAxis.PROSPERITY_EXCHANGE);
        VillageNamingProfile desertProfile = profileFor(
                VillageSignalSnapshot.builder().with(VillageSignal.DESERT, 1.0).build(),
                CivilizationalAxis.PROSPERITY_EXCHANGE
        );

        var standard = generator.generate(standardProfile, lexicon, 11L, 1).candidates().getFirst();
        var desert = generator.generate(desertProfile, lexicon, 11L, 1).candidates().getFirst();

        assertNotEquals(standard.renderedName(), desert.renderedName());
        assertTrue(desert.renderedName().toLowerCase().contains("deserta"));
        assertEquals(Set.of("standarda", "standardb"), Set.copyOf(desert.rootParts()));
    }

    @Test
    void doesNotLockOneGrowthRootIntoTheCandidateShortlist() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/emeraldcapitalism/village_naming/roots.json")) {
            assertNotNull(stream);
            RootLexicon lexicon = RootLexiconParser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
            VillageNamingProfile profile = new VillageNamingProfile(
                    VillageSignalSnapshot.builder().build(),
                    Map.of(),
                    Map.of(
                            CivilizationalAxis.GROWTH_FOOD, 1.0,
                            CivilizationalAxis.PROSPERITY_EXCHANGE, 0.4,
                            CivilizationalAxis.PROTECTION_STRENGTH, 0.4,
                            CivilizationalAxis.SETTLEMENT_DWELLING, 0.4
                    ),
                    Map.of()
            );
            VillageNameGenerator generator = new VillageNameGenerator();
            int occurrences = 0;
            int samples = 5_000;

            for (long seed = 0; seed < samples; seed++) {
                var candidates = generator.generate(profile, lexicon, seed, 4).candidates();
                int chosenIndex = (int) Math.floorMod(seed, Math.min(3, candidates.size()));
                if (candidates.get(chosenIndex).rootParts().contains("beil")) {
                    occurrences++;
                }
            }

            assertTrue(occurrences < samples * 0.10, "beil occurred in " + occurrences + " of " + samples + " names");
        }
    }

    @Test
    void returnsTraceableFailureWhenNoCompatibleSectionsExist() {
        RootLexicon lexicon = RootLexiconParser.parse(new StringReader("""
                {
                  "roots": [
                            {"root":"kel","section":"unmapped_section","meaning":"material","origin":"stone"}
                  ]
                }
                """));

        VillageNamingProfile profile = new VillageNamingProfileAnalyzer().analyze(VillageSignalSnapshot.builder().build());
        VillageNameGenerationResult result = new VillageNameGenerator().generate(profile, lexicon, 9L, 2);

        assertTrue(result.candidates().isEmpty());
        assertTrue(result.failureReasonOptional().isPresent());
        assertTrue(result.trace().debugSummary().contains("No compatible canonical roots"));
    }

    @Test
    void candidateLimitPreservesZeroOneAndLargerBounds() {
        RootLexicon lexicon = RootLexiconParser.parse(new StringReader("""
                {
                  "roots": [
                    {"root":"emra","section":"prosperity_exchange","meaning":"wealth"},
                    {"root":"selm","section":"bank_exchange_institutional","meaning":"trade"},
                    {"root":"tara","section":"quantity_sufficiency","meaning":"plenty"}
                  ]
                }
                """));
        VillageNamingProfile profile = profileFor(VillageSignalSnapshot.builder().build(),
                CivilizationalAxis.PROSPERITY_EXCHANGE);
        VillageNameGenerator generator = new VillageNameGenerator();

        assertTrue(generator.generate(profile, lexicon, 2L, 0).candidates().isEmpty());
        assertEquals(1, generator.generate(profile, lexicon, 2L, 1).candidates().size());
        assertTrue(generator.generate(profile, lexicon, 2L, 20).candidates().size() <= 20);
    }

    private static VillageNamingProfile profileFor(VillageSignalSnapshot signals, CivilizationalAxis axis) {
        return new VillageNamingProfile(signals, Map.of(), Map.of(axis, 1.0), Map.of());
    }
}
