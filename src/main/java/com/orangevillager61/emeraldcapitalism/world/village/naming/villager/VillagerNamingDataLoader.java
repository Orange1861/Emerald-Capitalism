package com.orangevillager61.emeraldcapitalism.world.village.naming.villager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Loads the generated villager naming system from the server data pack. */
public final class VillagerNamingDataLoader {

    public static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath(
            EmeraldCapitalism.MODID, "village_naming/villager_names.json");

    private static final Gson GSON = new Gson();

    private VillagerNamingDataLoader() {
    }

    public static Optional<VillagerNamingData> load(ResourceManager resourceManager) {
        try {
            Optional<Resource> resource = resourceManager.getResource(LOCATION);
            if (resource.isEmpty()) {
                EmeraldCapitalism.LOGGER.error(
                        "Missing generated villager naming system resource {}", LOCATION);
                return Optional.empty();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    resource.get().open(), StandardCharsets.UTF_8))) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                return Optional.of(VillagerNamingData.fromJson(root));
            }
        } catch (Exception exception) {
            EmeraldCapitalism.LOGGER.error(
                    "Failed to load generated villager naming system {}", LOCATION, exception);
            return Optional.empty();
        }
    }
}
