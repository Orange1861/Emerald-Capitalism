package com.orangevillager61.emeraldcapitalism.world.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The persisted bed color assigned to a village from its biome palette. */
public enum VillageColor {
    RED(0, "Red", Blocks.RED_BED),
    ORANGE(1, "Orange", Blocks.ORANGE_BED),
    YELLOW(2, "Yellow", Blocks.YELLOW_BED),
    WHITE(3, "White", Blocks.WHITE_BED),
    PINK(4, "Pink", Blocks.PINK_BED),
    GREEN(5, "Green", Blocks.GREEN_BED),
    LIME(6, "Lime", Blocks.LIME_BED);

    private static final int MAX_SERIALIZED_NAME_LENGTH = 16;
    private static final Codec<String> SERIALIZED_NAME_CODEC = Codec.STRING.validate(value ->
            value.length() <= MAX_SERIALIZED_NAME_LENGTH
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Village color exceeds "
                    + MAX_SERIALIZED_NAME_LENGTH + " characters"));
    public static final Codec<VillageColor> CODEC = SERIALIZED_NAME_CODEC.comapFlatMap(
            VillageColor::fromSerialized,
            VillageColor::serializedName);

    private final int networkId;
    private final String displayName;
    private final Block bedBlock;

    VillageColor(int networkId, String displayName, Block bedBlock) {
        this.networkId = networkId;
        this.displayName = displayName;
        this.bedBlock = bedBlock;
    }

    public String displayName() {
        return displayName;
    }

    public String serializedName() {
        return name();
    }

    public int networkId() {
        return networkId;
    }

    public Block bedBlock() {
        return bedBlock;
    }

    /** Returns the allowed colors for the village's vanilla palette. */
    public static List<VillageColor> optionsFor(VillageType villageType) {
        Objects.requireNonNull(villageType, "villageType");
        return switch (villageType) {
            case PLAINS -> List.of(RED, ORANGE, YELLOW, WHITE, PINK);
            case SAVANNA -> List.of(RED, PINK, ORANGE, WHITE);
            case TAIGA, SNOWY -> List.of(RED, PINK, WHITE);
            case DESERT -> List.of(RED, PINK, GREEN, LIME, WHITE);
        };
    }

    /** Selects one allowed color with equal probability from the level RNG. */
    public static VillageColor randomFor(VillageType villageType, RandomSource random) {
        List<VillageColor> options = optionsFor(villageType);
        Objects.requireNonNull(random, "random");
        return options.get(random.nextInt(options.size()));
    }

    public static VillageColor fromNetworkId(int networkId) {
        for (VillageColor color : values()) {
            if (color.networkId == networkId) {
                return color;
            }
        }
        return RED;
    }

    private static DataResult<VillageColor> fromSerialized(String serialized) {
        if (serialized == null) {
            return DataResult.error(() -> "Village color cannot be null");
        }
        try {
            return DataResult.success(valueOf(serialized.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Unknown village color: " + serialized);
        }
    }
}
