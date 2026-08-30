package com.orangevillager61.emeraldcapitalism.world.village.naming.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RootLexiconParser {
    private static final Gson GSON = new Gson();

    private RootLexiconParser() {
    }

    public static RootLexicon parse(Reader reader) {
        JsonObject json = GSON.fromJson(reader, JsonObject.class);
        JsonArray rootArray = json.getAsJsonArray("roots");
        if (rootArray == null) {
            throw new IllegalArgumentException("roots.json is missing required 'roots' array");
        }

        Map<String, ConceptRoot> uniqueRoots = new LinkedHashMap<>();
        for (JsonElement element : rootArray) {
            JsonObject obj = element.getAsJsonObject();
            String root = requiredString(obj, "root");
            String section = requiredString(obj, "section");
            String meaning = requiredString(obj, "meaning");
            String origin = obj.has("origin") ? obj.get("origin").getAsString() : "";
            List<String> tags = readTags(obj);
            double weightHint = obj.has("weightHint") ? obj.get("weightHint").getAsDouble() : 1.0;
            String usageTier = obj.has("usageTier") ? obj.get("usageTier").getAsString() : "default";
            boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            Map<String, String> dialects = readStringMap(obj, "dialects");
            Map<String, String> compatibilityMetadata = readStringMap(obj, "compatibility");

            ConceptRoot conceptRoot = new ConceptRoot(
                    root, section, meaning, origin, tags, weightHint, usageTier, enabled, dialects, compatibilityMetadata
            );

            ConceptRoot existing = uniqueRoots.get(root);
            if (existing == null) {
                uniqueRoots.put(root, conceptRoot);
            } else if (!existing.equals(conceptRoot)) {
                throw new IllegalArgumentException("Conflicting duplicate canonical root detected for '" + root + "'");
            }
        }

        return new RootLexicon(List.copyOf(uniqueRoots.values()));
    }

    private static String requiredString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Root entry missing required field: " + key);
        }
        return obj.get(key).getAsString();
    }

    private static List<String> readTags(JsonObject obj) {
        if (!obj.has("tags") || !obj.get("tags").isJsonArray()) {
            return List.of();
        }
        JsonArray arr = obj.getAsJsonArray("tags");
        List<String> tags = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            tags.add(el.getAsString());
        }
        return tags;
    }

    private static Map<String, String> readStringMap(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            return Map.of();
        }
        JsonObject metadata = obj.getAsJsonObject(key);
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : metadata.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getAsString());
        }
        return map;
    }
}
