package com.orangevillager61.emeraldcapitalism.world.village.naming;

import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.CivilizationalAxis;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageNamingProfile;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageNamingProfileAnalyzer;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignal;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignalSnapshot;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.ConceptRoot;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootLexicon;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootLexiconParser;
import com.orangevillager61.emeraldcapitalism.world.village.naming.generation.VillageNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VillageNamingAcceptanceEvidenceTest {

    @Test
    void dumpsSectionWeightsAndConfirmsBankWeight() throws Exception {
        RootLexicon lexicon = loadCanonicalLexicon();
        VillageSignalSnapshot signals = VillageSignalSnapshot.builder()
                .with(VillageSignal.VILLAGER_COUNT, 0.8)
                .with(VillageSignal.BED_COUNT, 0.7)
                .with(VillageSignal.BELL_CENTER_STRENGTH, 0.9)
                .with(VillageSignal.PATH_CONNECTEDNESS, 0.8)
                .with(VillageSignal.ROUTE_CONNECTIVITY, 0.7)
                .with(VillageSignal.BARREL_COUNT, 0.6)
                .with(VillageSignal.FARMLAND_COUNT, 0.6)
                .with(VillageSignal.FARMER_POI_COUNT, 0.5)
                .build();
        VillageNamingProfile profile = new VillageNamingProfileAnalyzer().analyze(signals);

        var result = new VillageNameGenerator().generate(profile, lexicon, 20260817L, 1);
        double bankWeight = result.trace().boostedSections().getOrDefault("bank_exchange_institutional", 0.0);
        System.out.println("ACCEPTANCE SECTION WEIGHTS " + result.trace().boostedSections());
        assertFalse(result.candidates().isEmpty());
        assertTrue(bankWeight > 0.0, "bank_exchange_institutional must carry a nonzero weight");
    }

    @Test
    void provesDialectForms() throws Exception {
        RootLexicon lexicon = RootLexiconParser.parse(new StringReader("""
                {
                  "roots": [
                    {"root":"standardA","section":"prosperity_exchange","meaning":"trade","dialects":{"desert":"desertA"}},
                    {"root":"standardB","section":"bank_exchange_institutional","meaning":"wealth","dialects":{"desert":"desertB"}}
                  ]
                }
                """));
        VillageNameGenerator generator = new VillageNameGenerator();
        VillageNamingProfile plains = profileFor(VillageSignalSnapshot.builder().build(), CivilizationalAxis.PROSPERITY_EXCHANGE);
        VillageNamingProfile desert = profileFor(
                VillageSignalSnapshot.builder().with(VillageSignal.DESERT, 1.0).build(),
                CivilizationalAxis.PROSPERITY_EXCHANGE
        );
        String plainsName = generator.generate(plains, lexicon, 11L, 1).candidates().getFirst().renderedName();
        String desertName = generator.generate(desert, lexicon, 11L, 1).candidates().getFirst().renderedName();
        System.out.printf("ACCEPTANCE DIALECT PROOF plains=%s desert=%s%n", plainsName, desertName);
        assertNotEquals(plainsName, desertName);

    }

    @Test
    void dumpsTwentyDeterministicBiomeVillageNames() throws Exception {
        RootLexicon lexicon = loadCanonicalLexicon();
        VillageNameGenerator generator = new VillageNameGenerator();
        VillageNamingProfileAnalyzer analyzer = new VillageNamingProfileAnalyzer();
        List<BiomeCase> cases = List.of(
                new BiomeCase("plains", VillageSignal.PLAINS),
                new BiomeCase("desert", VillageSignal.DESERT),
                new BiomeCase("savanna", VillageSignal.SAVANNA),
                new BiomeCase("taiga", VillageSignal.TAIGA),
                new BiomeCase("snowy", VillageSignal.SNOWY)
        );

        System.out.println("ACCEPTANCE VILLAGE TABLE");
        System.out.println("village name | biome | concatenation | seam-repaired | root 1 | section 1 | root 2 | section 2");
        int rerolls = 0;
        for (int i = 0; i < 20; i++) {
            BiomeCase biome = cases.get(i % cases.size());
            VillageSignalSnapshot.Builder signals = VillageSignalSnapshot.builder()
                    .with(VillageSignal.VILLAGER_COUNT, 0.55 + ((i % 4) * 0.1))
                    .with(VillageSignal.BED_COUNT, 0.5 + ((i % 3) * 0.1))
                    .with(VillageSignal.BELL_CENTER_STRENGTH, 0.7)
                    .with(VillageSignal.PATH_CONNECTEDNESS, 0.55 + ((i % 2) * 0.2))
                    .with(VillageSignal.ROUTE_CONNECTIVITY, 0.45 + ((i % 4) * 0.1))
                    .with(VillageSignal.FARMLAND_COUNT, 0.35 + ((i % 3) * 0.1))
                    .with(VillageSignal.FARMER_POI_COUNT, 0.35)
                    .with(VillageSignal.COMPOSTER_COUNT, 0.25)
                    .with(biome.signal(), 1.0);
            var result = generator.generate(analyzer.analyze(signals.build()), lexicon, 5000L + i, 1);
            assertFalse(result.candidates().isEmpty());
            rerolls += result.trace().rerollCount();
            var candidate = result.candidates().getFirst();
            ConceptRoot first = findRoot(lexicon, candidate.rootParts().getFirst());
            ConceptRoot second = findRoot(lexicon, candidate.rootParts().getLast());
            String firstForm = first.formForDialect(biome.name());
            String secondForm = second.formForDialect(biome.name());
            String concatenation = capitalize(firstForm + secondForm);
            String seamRepaired = capitalize(VillageNameGenerator.joinPlaceName(
                    firstForm, secondForm, first.stratum()));
            assertEquals(candidate.renderedName(), seamRepaired);
            System.out.printf("%s | %s | %s | %s | %s | %s | %s | %s%n",
                    candidate.renderedName(), biome.name(), concatenation, seamRepaired,
                    first.root(), first.section(), second.root(), second.section());
        }
        System.out.printf("ACCEPTANCE VILLAGE REROLL RATE %.2f%% (%d/20)%n", rerolls * 5.0, rerolls, 20);
    }

    private static RootLexicon loadCanonicalLexicon() throws Exception {
        try (var stream = VillageNamingAcceptanceEvidenceTest.class
                .getResourceAsStream("/data/emeraldcapitalism/village_naming/roots.json")) {
            assertNotNull(stream);
            return RootLexiconParser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    private static ConceptRoot findRoot(RootLexicon lexicon, String root) {
        return lexicon.roots().stream().filter(candidate -> candidate.root().equals(root)).findFirst().orElseThrow();
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static VillageNamingProfile profileFor(VillageSignalSnapshot signals, CivilizationalAxis axis) {
        return new VillageNamingProfile(signals, Map.of(), axis == null ? Map.of() : Map.of(axis, 1.0), Map.of());
    }

    private record BiomeCase(String name, VillageSignal signal) {
    }
}
