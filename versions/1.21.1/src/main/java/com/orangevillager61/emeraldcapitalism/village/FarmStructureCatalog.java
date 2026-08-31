package com.orangevillager61.emeraldcapitalism.village;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog of known village farm structure identifiers by biome.
 */
public final class FarmStructureCatalog {

    // Vanilla farm structure names by biome.
    // Matched by name against pool element string representations.
    public static final Map<String, List<String>> VANILLA_FARM_STRUCTURES = new HashMap<>();

    // Extra mod-only farms that were intended for pool injection.
    public static final Map<String, List<String>> MOD_EXCLUSIVE_FARM_STRUCTURES = Map.of(
            "plains", List.of("plains_large_farm_2")
    );

    static {
        VANILLA_FARM_STRUCTURES.put("plains", List.of(
                "plains_small_farm_1",
                "plains_large_farm_1"
        ));
        VANILLA_FARM_STRUCTURES.put("desert", List.of(
                "desert_farm_1",
                "desert_farm_2",
                "desert_large_farm_1"
        ));
        VANILLA_FARM_STRUCTURES.put("savanna", List.of(
                "savanna_small_farm",
                "savanna_large_farm_1",
                "savanna_large_farm_2"
        ));
        VANILLA_FARM_STRUCTURES.put("taiga", List.of(
                "taiga_small_farm_1",
                "taiga_large_farm_1",
                "taiga_large_farm_2"
        ));
        VANILLA_FARM_STRUCTURES.put("snowy", List.of(
                "snowy_farm_1",
                "snowy_farm_2"
        ));
    }

    private FarmStructureCatalog() {
    }

    /**
     * Checks whether an element string contains any known farm name.
     *
     * @param elementStr structure pool element toString() value
     * @param farmNames configured farm name list
     * @return matched farm name or null
     */
    public static String findMatchingFarm(String elementStr, List<String> farmNames) {
        if (elementStr == null) {
            return null;
        }
        for (String farmName : farmNames) {
            if (elementStr.contains(farmName)) {
                return farmName;
            }
        }
        return null;
    }
}
