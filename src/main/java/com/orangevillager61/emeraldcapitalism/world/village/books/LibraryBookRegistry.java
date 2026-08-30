package com.orangevillager61.emeraldcapitalism.world.village.books;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resource-reloadable registry for authored book definitions. */
public final class LibraryBookRegistry {
    private static final String RESOURCE_DIRECTORY = "library_books";
    private static final int MAX_TITLE_LENGTH = 32;
    private static final int MAX_AUTHOR_LENGTH = 128;
    private static final int MAX_PAGE_COUNT = 100;
    private static final int MAX_PAGE_LENGTH = 8192;
    private static final int BOOKS_PER_LIBRARY_SHELF = 6;
    private static volatile Map<String, LibraryBookDefinition> entries = Map.of();

    private LibraryBookRegistry() {
    }

    public static void load(ResourceManager resourceManager) {
        Map<String, LibraryBookDefinition> loaded = new LinkedHashMap<>();
        List<Map.Entry<ResourceLocation, Resource>> resources = new ArrayList<>(
                resourceManager.listResources(RESOURCE_DIRECTORY,
                                location -> location.getPath().endsWith(".json"))
                        .entrySet());
        resources.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)));

        for (Map.Entry<ResourceLocation, Resource> entry : resources) {
            ResourceLocation location = entry.getKey();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    entry.getValue().open(), StandardCharsets.UTF_8))) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    throw new IllegalArgumentException("root must be an object");
                }
                var object = root.getAsJsonObject();
                String title = requiredString(object, "title", MAX_TITLE_LENGTH);
                String author = requiredString(object, "author", MAX_AUTHOR_LENGTH);
                LibraryBookRarity rarity = LibraryBookRarity.fromId(
                                requiredString(object, "rarity", 64))
                        .orElseThrow(() -> new IllegalArgumentException("unsupported rarity"));
                JsonArray pageArray = object.getAsJsonArray("pages");
                if (pageArray == null || pageArray.isEmpty() || pageArray.size() > MAX_PAGE_COUNT) {
                    throw new IllegalArgumentException("pages must contain 1-" + MAX_PAGE_COUNT + " entries");
                }
                List<String> pages = new ArrayList<>(pageArray.size());
                for (JsonElement pageElement : pageArray) {
                    if (!pageElement.isJsonPrimitive()
                            || !pageElement.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("every page must be a string");
                    }
                    String page = pageElement.getAsString().trim();
                    if (page.isEmpty() || page.length() > MAX_PAGE_LENGTH) {
                        throw new IllegalArgumentException(
                                "page must be 1-" + MAX_PAGE_LENGTH + " characters");
                    }
                    pages.add(page);
                }

                String id = location.toString();
                LibraryBookDefinition definition = new LibraryBookDefinition(
                        id, title, author, rarity, pages);
                if (loaded.put(id, definition) != null) {
                    throw new IllegalArgumentException("duplicate book id " + id);
                }
            } catch (Exception exception) {
                EmeraldCapitalism.LOGGER.error(
                        "Ignoring invalid authored book {}: {}", location, exception.getMessage());
            }
        }

        entries = Map.copyOf(loaded);
        EmeraldCapitalism.LOGGER.info("Loaded {} authored book definitions", entries.size());
    }

    public static List<LibraryBookDefinition> entries() {
        return entries.values().stream()
                .sorted(Comparator.comparing(LibraryBookDefinition::id))
                .toList();
    }

    public static List<LibraryBookDefinition> entries(LibraryBookRarity rarity) {
        return entries.values().stream()
                .filter(book -> book.rarity() == rarity)
                .sorted(Comparator.comparing(LibraryBookDefinition::id))
                .toList();
    }

    public static Optional<LibraryBookDefinition> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * Selects up to six unique random-pool books for one library shelf.
     * Bank Rule and Village Manager books are deliberately excluded: their
     * definitions are available for deterministic owners, never this pool.
     */
    public static List<LibraryBookDefinition> selectLibraryBooks(RandomSource random) {
        List<LibraryBookDefinition> selected = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        while (selected.size() < BOOKS_PER_LIBRARY_SHELF) {
            List<LibraryBookRarity> availableRarities = new ArrayList<>();
            int totalWeight = 0;
            for (LibraryBookRarity rarity : LibraryBookRarity.values()) {
                if (!rarity.isRandomLibraryPool() || rarity.libraryPoolWeight() <= 0) {
                    continue;
                }
                boolean available = entries.values().stream().anyMatch(book ->
                        book.rarity() == rarity && !selectedIds.contains(book.id()));
                if (available) {
                    availableRarities.add(rarity);
                    totalWeight += rarity.libraryPoolWeight();
                }
            }
            if (totalWeight == 0) {
                break;
            }

            int roll = random.nextInt(totalWeight);
            LibraryBookRarity chosenRarity = availableRarities.getLast();
            for (LibraryBookRarity rarity : availableRarities) {
                if (roll < rarity.libraryPoolWeight()) {
                    chosenRarity = rarity;
                    break;
                }
                roll -= rarity.libraryPoolWeight();
            }

            List<LibraryBookDefinition> candidates = entries(chosenRarity).stream()
                    .filter(book -> !selectedIds.contains(book.id()))
                    .toList();
            LibraryBookDefinition chosen = candidates.get(random.nextInt(candidates.size()));
            selected.add(chosen);
            selectedIds.add(chosen.id());
        }
        return List.copyOf(selected);
    }

    private static String requiredString(com.google.gson.JsonObject object, String key, int maximum) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String value = element.getAsString().trim();
        if (value.isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException(key + " must be 1-" + maximum + " characters");
        }
        return value;
    }
}
