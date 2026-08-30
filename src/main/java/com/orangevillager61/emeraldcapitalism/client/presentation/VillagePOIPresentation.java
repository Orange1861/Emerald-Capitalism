package com.orangevillager61.emeraldcapitalism.client.presentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure sorting, grouping, and capacity preparation for the village ledger. */
public final class VillagePOIPresentation {
    private static final Set<String> INFRASTRUCTURE_TYPES = Set.of("Bank", "Village Ledger");

    private VillagePOIPresentation() {
    }

    public enum SortMode {
        NAME_ASC, NAME_DESC, PROFESSION_ASC, PROFESSION_DESC
    }

    public record VillagerSnapshot(String name, String profession, float health,
                                   int opinion, String bedPosition) {
    }

    public record JobSiteSnapshot(String type, String position, boolean claimed, String villagerName) {
    }

    public record JobSiteRow(String text, String status, String villagerName,
                             boolean header, boolean claimed, PresentationStyle style) {
    }

    public record BedCapacity(int villagers, int totalBeds, int assignedBeds, int availableBeds) {
        public int difference() {
            return totalBeds - villagers;
        }
    }

    public static List<VillagerSnapshot> sortVillagers(List<VillagerSnapshot> villagers, SortMode mode) {
        List<VillagerSnapshot> sorted = new ArrayList<>(villagers);
        Comparator<VillagerSnapshot> comparator = switch (mode) {
            case NAME_ASC -> Comparator.comparing(VillagerSnapshot::name, String.CASE_INSENSITIVE_ORDER);
            case NAME_DESC -> Comparator.comparing(VillagerSnapshot::name, String.CASE_INSENSITIVE_ORDER).reversed();
            case PROFESSION_ASC -> Comparator.comparing(VillagerSnapshot::profession, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(VillagerSnapshot::name, String.CASE_INSENSITIVE_ORDER);
            case PROFESSION_DESC -> Comparator.comparing(VillagerSnapshot::profession, String.CASE_INSENSITIVE_ORDER).reversed()
                    .thenComparing(VillagerSnapshot::name, String.CASE_INSENSITIVE_ORDER);
        };
        sorted.sort(comparator);
        return List.copyOf(sorted);
    }

    public static List<JobSiteRow> groupJobSites(List<JobSiteSnapshot> jobSites) {
        Map<String, List<JobSiteSnapshot>> byType = new TreeMap<>();
        for (JobSiteSnapshot entry : jobSites) {
            byType.computeIfAbsent(entry.type(), ignored -> new ArrayList<>()).add(entry);
        }

        List<JobSiteRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<JobSiteSnapshot>> group : byType.entrySet()) {
            long claimed = group.getValue().stream().filter(JobSiteSnapshot::claimed).count();
            long unclaimed = group.getValue().size() - claimed;
            PresentationStyle style = INFRASTRUCTURE_TYPES.contains(group.getKey())
                    ? PresentationStyle.INFRASTRUCTURE : PresentationStyle.WARNING;
            rows.add(new JobSiteRow(group.getKey() + " (" + claimed + " claimed, " + unclaimed + " unclaimed)",
                    null, "", true, false, style));
            for (JobSiteSnapshot entry : group.getValue()) {
                rows.add(new JobSiteRow("  " + entry.position(), entry.claimed() ? "Claimed" : "Unclaimed",
                        entry.villagerName(), false, entry.claimed(), style));
            }
        }
        return List.copyOf(rows);
    }

    public static BedCapacity bedCapacity(int villagers, int totalBeds, int assignedBeds) {
        return new BedCapacity(villagers, totalBeds, assignedBeds,
                Math.max(0, totalBeds - assignedBeds));
    }

    public static PresentationStyle opinionStyle(int opinion) {
        return opinion < 0 ? PresentationStyle.NEGATIVE
                : opinion > 0 ? PresentationStyle.POSITIVE : PresentationStyle.NEUTRAL;
    }
}
