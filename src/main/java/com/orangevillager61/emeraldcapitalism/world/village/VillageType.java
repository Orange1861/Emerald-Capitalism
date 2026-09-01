package com.orangevillager61.emeraldcapitalism.world.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Locale;

/** The five vanilla village palettes that are durable village identity. */
public enum VillageType {
    PLAINS("Plains"),
    SAVANNA("Savanna"),
    TAIGA("Taiga"),
    SNOWY("Snowy"),
    DESERT("Desert");

    private static final int MAX_SERIALIZED_NAME_LENGTH = 16;
    private static final Codec<String> SERIALIZED_NAME_CODEC = Codec.STRING.validate(value ->
            value.length() <= MAX_SERIALIZED_NAME_LENGTH
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Village type exceeds "
                    + MAX_SERIALIZED_NAME_LENGTH + " characters"));
    public static final Codec<VillageType> CODEC = SERIALIZED_NAME_CODEC.comapFlatMap(
            VillageType::fromSerialized,
            VillageType::serializedName);

    private final String displayName;

    VillageType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public String serializedName() {
        return name();
    }

    /** Converts the existing uppercase biome-palette value into the durable type. */
    public static VillageType fromBiomeType(String biomeType) {
        if (biomeType == null) {
            return PLAINS;
        }
        try {
            return valueOf(biomeType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return PLAINS;
        }
    }

    private static DataResult<VillageType> fromSerialized(String serialized) {
        try {
            return DataResult.success(valueOf(serialized.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Unknown village type: " + serialized);
        }
    }
}
