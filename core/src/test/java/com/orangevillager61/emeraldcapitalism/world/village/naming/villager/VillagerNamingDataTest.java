package com.orangevillager61.emeraldcapitalism.world.village.naming.villager;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VillagerNamingDataTest {

    private static VillagerNamingData data() throws Exception {
        try (var stream = VillagerNamingDataTest.class.getResourceAsStream(
                "/data/emeraldcapitalism/village_naming/villager_names.json")) {
            assertNotNull(stream);
            return VillagerNamingData.fromJson(JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    @Test
    void loadsClosedPoolsAndTheTwelveSharedDriftRules() throws Exception {
        VillagerNamingData data = data();

        assertEquals(20, data.firstElements().size());
        assertEquals(20, data.secondElements().size());
        assertEquals(400, data.pool("plains").first().size() * data.pool("plains").second().size());
        assertEquals(289, data.pool("savanna").first().size() * data.pool("savanna").second().size());
        assertEquals(169, data.pool("desert").first().size() * data.pool("desert").second().size());
        assertEquals(100, data.pool("taiga").first().size() * data.pool("taiga").second().size());
        assertEquals(81, data.pool("snowy").first().size() * data.pool("snowy").second().size());
        assertEquals(12, data.driftRules().size());
        assertEquals(1, data.driftAssignment().inverseGroups().size());
        assertEquals("ascending_rule_index", data.driftAssignment().ruleOrder());
        assertEquals(List.of("Kinniken"), data.specialFirstNames().names());
        assertEquals(0.0001, data.specialFirstNames().selectionChance(), 0.0);
    }

    @Test
    void reproducesJunctionBiomeAndRegionalBynameRules() throws Exception {
        VillagerNamingData data = data();

        assertEquals("mukkem", data.baseForm("muk", "kem"),
                "gemination must win over stop deletion");
        assertEquals("mutem", data.baseForm("muk", "tem"));
        assertEquals("mukkem", data.regionalForm("mukkem", "plains"));
        assertEquals("mukem", data.regionalForm("mukkem", "savanna"));
        assertEquals("flichi", data.byname("fletcher", "desert"));
        assertEquals("flechi", data.byname("fletcher", "plains"));
        assertEquals("Bemsunin", capitalize(data.affix("bemsu", "in")));
        assertEquals("Wloni", capitalize(data.byname("shepherd", "plains")));
        assertEquals("Rodani", capitalize(data.affix("roda", "i")));
        assertEquals("Koldi", capitalize(data.affix("kold", "i")));
        assertEquals("", data.byname("mayor", "plains"));

        Map<String, String> plains = Map.ofEntries(
                Map.entry("armorer", "armini"), Map.entry("butcher", "smookani"),
                Map.entry("cartographer", "mopini"), Map.entry("cleric", "buti"),
                Map.entry("farmer", "kompi"), Map.entry("fisherman", "rodani"),
                Map.entry("fletcher", "flechi"), Map.entry("leatherworker", "koldi"),
                Map.entry("librarian", "lekti"), Map.entry("mason", "meisoni"),
                Map.entry("shepherd", "wloni"), Map.entry("toolsmith", "pikani"),
                Map.entry("weaponsmith", "wepani"), Map.entry("banker", "indani"),
                Map.entry("emeraldsmith", "emsmeti"), Map.entry("merchant", "treidi"),
                Map.entry("lumberjack", "wokoti"));
        plains.forEach((profession, expected) -> assertEquals(expected, data.byname(profession, "plains")));

        assertEquals("smoogani", data.byname("butcher", "savanna"));
        assertEquals("wipani", data.byname("weaponsmith", "desert"));
        assertEquals("mupini", data.byname("cartographer", "taiga"));
        assertEquals("koli", data.byname("leatherworker", "snowy"));
    }

    @Test
    void appliesDriftToSuffixesAndBynamesWithoutTouchingOriginData() throws Exception {
        VillagerNamingData data = data();

        assertEquals("benkem", data.applyDrift(List.of("D9"), "penkem"));
        assertEquals("kistul", data.applyDrift(List.of("D10"), "gistul"));
        assertEquals("kernem", data.applyDrift(List.of("D3"), "kirnim"));
        assertEquals("ek", data.applyDrift(List.of("D3"), "ik"));
    }

    @Test
    void rendersTheConfiguredOriginParticleOnlyAfterTheVillageHasAName() throws Exception {
        VillagerNamingData data = data();

        assertEquals("Se Aster Ford", data.originParticleText("Aster Ford"));
        assertEquals("Bi-Aster Ford", data.mayorOriginParticleText("Aster Ford"));
        assertEquals("", data.originParticleText(null));
        assertEquals("", data.originParticleText("   "));
        assertEquals("", data.mayorOriginParticleText(null));
        assertEquals("", data.mayorOriginParticleText("   "));
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
