package com.orangevillager61.emeraldcapitalism.world.village.naming.data;

import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.CivilizationalAxis;

import java.util.Locale;
import java.util.Optional;

public enum RootSection {
    PROSPERITY_EXCHANGE("prosperity_exchange", CivilizationalAxis.PROSPERITY_EXCHANGE),
    BANK_EXCHANGE_INSTITUTIONAL("bank_exchange_institutional", CivilizationalAxis.PROSPERITY_EXCHANGE),
    QUANTITY_SUFFICIENCY("quantity_sufficiency", CivilizationalAxis.PROSPERITY_EXCHANGE),
    PROTECTION_STRENGTH("protection_strength_communal_defense", CivilizationalAxis.PROTECTION_STRENGTH),
    DANGER_DECAY("danger_decay_hostile_pressure", CivilizationalAxis.DANGER_DECAY),
    KNOWLEDGE_ENCHANTMENT("knowledge_enchantment_ritual_skill", CivilizationalAxis.KNOWLEDGE_ENCHANTMENT),
    MENTAL_CAPACITY("mental_capacity", CivilizationalAxis.KNOWLEDGE_ENCHANTMENT),
    CONCEPTS("concepts", CivilizationalAxis.KNOWLEDGE_ENCHANTMENT),
    SPEECH_AND_UNDERSTANDING("speech_and_understanding", CivilizationalAxis.KNOWLEDGE_ENCHANTMENT),
    CRAFT_TRANSFORMATION("labor_making_transformation", CivilizationalAxis.CRAFT_TRANSFORMATION),
    SETTLEMENT_DWELLING("settlement_dwelling_social_order", CivilizationalAxis.SETTLEMENT_DWELLING),
    BODY_AND_PERSON("body_and_person", CivilizationalAxis.SETTLEMENT_DWELLING),
    GROWTH_FOOD("growth_food_worked_land", CivilizationalAxis.GROWTH_FOOD),
    MEMORY_INHERITANCE("memory_age_inheritance", CivilizationalAxis.MEMORY_INHERITANCE),
    TIME("time", CivilizationalAxis.MEMORY_INHERITANCE),
    THE_BUILDERS("the_builders", CivilizationalAxis.MEMORY_INHERITANCE),
    BIOME_MATERIAL_IDENTITY("biome_material_identity", CivilizationalAxis.GROWTH_FOOD),
    BUILDINGS_WORKSTATIONS("buildings_workstations_village_function", CivilizationalAxis.CRAFT_TRANSFORMATION),
    COAST_FISHING_WATER_TRAVEL("coast_fishing_water_travel", CivilizationalAxis.GROWTH_FOOD),
    CONNECTION_TRANSIT("connection_transit", CivilizationalAxis.PROSPERITY_EXCHANGE),
    LAYOUT_DENSITY_SCALE("layout_density_village_scale", CivilizationalAxis.SETTLEMENT_DWELLING),
    NEARBY_STRUCTURES_ADJACENCY("nearby_structures_adjacency", CivilizationalAxis.MEMORY_INHERITANCE),
    SHELTERED_ENCLOSED_TERRAIN("sheltered_enclosed_terrain", CivilizationalAxis.SETTLEMENT_DWELLING),
    TERRAIN_ROUTE_COAST("terrain_route_coast_natural_placement", CivilizationalAxis.PROSPERITY_EXCHANGE),
    PROFESSION_VOCABULARY("profession_vocabulary_added_for_the_mod_professions", CivilizationalAxis.CRAFT_TRANSFORMATION);

    private final String id;
    private final CivilizationalAxis defaultAxis;

    RootSection(String id, CivilizationalAxis defaultAxis) {
        this.id = id;
        this.defaultAxis = defaultAxis;
    }

    public String id() {
        return id;
    }

    public Optional<CivilizationalAxis> defaultAxis() {
        return Optional.ofNullable(defaultAxis);
    }

    public static Optional<RootSection> fromId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawId.toLowerCase(Locale.ROOT);
        for (RootSection section : values()) {
            if (section.id.equals(normalized)) {
                return Optional.of(section);
            }
        }
        return Optional.empty();
    }
}
