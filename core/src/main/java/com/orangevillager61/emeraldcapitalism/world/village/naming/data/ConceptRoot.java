package com.orangevillager61.emeraldcapitalism.world.village.naming.data;

import java.util.List;
import java.util.Map;

public record ConceptRoot(
        String root,
        String section,
        String meaning,
        String origin,
        List<String> tags,
        double weightHint,
        String usageTier,
        boolean enabled,
        Map<String, String> dialects,
        Map<String, String> compatibilityMetadata
) {
    public ConceptRoot {
        tags = tags == null ? List.of() : List.copyOf(tags);
        dialects = dialects == null ? Map.of() : Map.copyOf(dialects);
        compatibilityMetadata = compatibilityMetadata == null ? Map.of() : Map.copyOf(compatibilityMetadata);
    }

    public String formForDialect(String dialect) {
        if (dialect == null || dialect.isBlank()) {
            return root;
        }
        return dialects.getOrDefault(dialect, root);
    }

    /** The converter exports stratum as a tag so C1 can receive the modifier's history. */
    public String stratum() {
        return tags.stream()
                .filter(tag -> tag.startsWith("stratum:"))
                .map(tag -> tag.substring("stratum:".length()))
                .findFirst()
                .orElse("5");
    }
}
