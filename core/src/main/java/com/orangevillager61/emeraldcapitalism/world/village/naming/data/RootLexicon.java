package com.orangevillager61.emeraldcapitalism.world.village.naming.data;

import java.util.*;

public final class RootLexicon {
    private final List<ConceptRoot> allRoots;
    private final List<ConceptRoot> enabledRoots;
    private final Map<String, List<ConceptRoot>> bySection;
    private final Map<String, List<ConceptRoot>> byTag;

    public RootLexicon(List<ConceptRoot> roots) {
        this.allRoots = List.copyOf(roots);
        this.enabledRoots = roots.stream().filter(ConceptRoot::enabled).toList();

        Map<String, List<ConceptRoot>> sectionMap = new HashMap<>();
        Map<String, List<ConceptRoot>> tagMap = new HashMap<>();

        for (ConceptRoot root : enabledRoots) {
            sectionMap.computeIfAbsent(root.section(), ignored -> new ArrayList<>()).add(root);
            for (String tag : root.tags()) {
                tagMap.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(root);
            }
        }

        this.bySection = freeze(sectionMap);
        this.byTag = freeze(tagMap);
    }

    private static Map<String, List<ConceptRoot>> freeze(Map<String, List<ConceptRoot>> mutable) {
        Map<String, List<ConceptRoot>> frozen = new HashMap<>();
        for (Map.Entry<String, List<ConceptRoot>> entry : mutable.entrySet()) {
            List<ConceptRoot> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(ConceptRoot::root))
                    .toList();
            frozen.put(entry.getKey(), sorted);
        }
        return Collections.unmodifiableMap(frozen);
    }

    public List<ConceptRoot> roots() {
        return allRoots;
    }

    public List<ConceptRoot> enabledRoots() {
        return enabledRoots;
    }

    public List<ConceptRoot> rootsBySection(String section) {
        return bySection.getOrDefault(section, List.of());
    }

    public List<ConceptRoot> rootsByTag(String tag) {
        return byTag.getOrDefault(tag, List.of());
    }

    public Set<String> sections() {
        return bySection.keySet();
    }
}
