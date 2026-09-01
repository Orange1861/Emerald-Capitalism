package com.orangevillager61.emeraldcapitalism.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.market.MarketItemConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resource-reloadable registry of all market entries. */
public final class MarketRegistry {
    private static final String RESOURCE_DIRECTORY = "market";
    private static volatile Map<String, MarketItem> entries = Map.of();
    private static volatile List<MarketItem> sortedEntries = List.of();

    private MarketRegistry() {
    }

    public static void load(ResourceManager resourceManager) {
        Map<String, MarketItem> loaded = new LinkedHashMap<>();
        List<Map.Entry<ResourceLocation, Resource>> resources = new ArrayList<>(
                resourceManager.listResources(RESOURCE_DIRECTORY, location -> location.getPath().endsWith(".json"))
                        .entrySet());
        resources.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)));

        for (Map.Entry<ResourceLocation, Resource> entry : resources) {
            ResourceLocation location = entry.getKey();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    entry.getValue().open(), StandardCharsets.UTF_8))) {
                JsonElement json = JsonParser.parseReader(reader);
                MarketDefinition definition = MarketDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                        .getOrThrow(error -> new IllegalArgumentException(error));
                MarketItemConfig config = definition.toCoreConfig();
                ResourceLocation itemId = parseItemId(config.id());
                Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemId);
                if (item.isEmpty()) {
                    throw new IllegalArgumentException("unknown item " + itemId);
                }
                if (loaded.put(config.id(), new MarketItem(config, itemId, item.get())) != null) {
                    throw new IllegalArgumentException("duplicate market id " + config.id());
                }
            } catch (Exception exception) {
                EmeraldCapitalism.LOGGER.error(
                        "Ignoring invalid market definition {}: {}", location, exception.getMessage());
            }
        }
        entries = Map.copyOf(loaded);
        sortedEntries = entries.values().stream()
                .sorted(Comparator.comparing(item -> item.config().id()))
                .toList();
        EmeraldCapitalism.LOGGER.info("Loaded {} market item definitions", entries.size());
    }

    public static List<MarketItem> entries() {
        return sortedEntries;
    }

    public static Optional<MarketItem> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    private static ResourceLocation parseItemId(String id) {
        return id.indexOf(':') >= 0
                ? ResourceLocation.parse(id)
                : ResourceLocation.withDefaultNamespace(id);
    }
}
