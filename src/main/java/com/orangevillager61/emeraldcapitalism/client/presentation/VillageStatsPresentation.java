package com.orangevillager61.emeraldcapitalism.client.presentation;

import java.util.ArrayList;
import java.util.List;

/** Pure derived values and semantic rows for the village statistics tab. */
public final class VillageStatsPresentation {
    private VillageStatsPresentation() {
    }

    public record Snapshot(int villageOpinion, String villageId, String bellPosition,
                           int villagers, int totalBeds, int assignedBeds,
                           int farmland, int doors, int repairQueue, int ironGolemCapacity,
                           int ironGolemsPresent, int emeraldGolemsPresent,
                           int emeraldGolemCapacity, String bankName) {
        public Snapshot(int villageOpinion, String villageId, String bellPosition,
                        int villagers, int totalBeds, int assignedBeds,
                        int farmland, int repairQueue, int ironGolemCapacity,
                        int ironGolemsPresent, int emeraldGolemsPresent,
                        int emeraldGolemCapacity, String bankName) {
            this(villageOpinion, villageId, bellPosition, villagers, totalBeds, assignedBeds,
                    farmland, 0, repairQueue, ironGolemCapacity, ironGolemsPresent,
                    emeraldGolemsPresent, emeraldGolemCapacity, bankName);
        }
    }

    public record StatLine(String label, String value, PresentationStyle style, boolean separator) {
    }

    public static List<StatLine> lines(Snapshot snapshot) {
        List<StatLine> lines = new ArrayList<>();
        lines.add(new StatLine("Village Opinion of You", String.valueOf(snapshot.villageOpinion()),
                VillagePOIPresentation.opinionStyle(snapshot.villageOpinion()), false));
        lines.add(separator());

        lines.add(new StatLine("Villagers", String.valueOf(snapshot.villagers()), PresentationStyle.NEUTRAL, false));
        lines.add(new StatLine("Total Beds", String.valueOf(snapshot.totalBeds()), PresentationStyle.POSITIVE, false));
        lines.add(new StatLine("Assigned Beds", String.valueOf(snapshot.assignedBeds()), PresentationStyle.WARNING, false));
        int availableBeds = Math.max(0, snapshot.totalBeds() - snapshot.assignedBeds());
        lines.add(new StatLine("Available Beds", String.valueOf(availableBeds), PresentationStyle.POSITIVE, false));
        lines.add(separator());

        int difference = snapshot.totalBeds() - snapshot.villagers();
        lines.add(new StatLine("Capacity", snapshot.villagers() + " villagers / " + snapshot.totalBeds() + " beds",
                difference >= 0 ? PresentationStyle.POSITIVE : PresentationStyle.NEGATIVE, false));
        if (difference > 0) {
            lines.add(new StatLine("Growth Room", difference + " bed" + (difference != 1 ? "s" : ""),
                    PresentationStyle.POSITIVE, false));
        } else if (difference < 0) {
            int deficit = -difference;
            lines.add(new StatLine("Bed Deficit", deficit + " bed" + (deficit != 1 ? "s" : "") + " needed",
                    PresentationStyle.NEGATIVE, false));
        } else {
            lines.add(new StatLine("Status", "At capacity", PresentationStyle.WARNING, false));
        }
        lines.add(separator());

        int healthy = Math.max(0, snapshot.farmland() - snapshot.repairQueue());
        lines.add(new StatLine("Farmland", String.valueOf(snapshot.farmland()), PresentationStyle.NEUTRAL, false));
        lines.add(new StatLine("Healthy", String.valueOf(healthy), PresentationStyle.POSITIVE, false));
        lines.add(new StatLine("Needs Repair", String.valueOf(snapshot.repairQueue()),
                snapshot.repairQueue() > 0 ? PresentationStyle.NEGATIVE : PresentationStyle.POSITIVE, false));
        lines.add(new StatLine("Doors", String.valueOf(snapshot.doors()), PresentationStyle.NEUTRAL, false));
        lines.add(separator());

        int golemDifference = snapshot.ironGolemCapacity() - snapshot.ironGolemsPresent();
        lines.add(new StatLine("Iron Golems", String.valueOf(snapshot.ironGolemsPresent()), PresentationStyle.NEUTRAL, false));
        lines.add(new StatLine("Iron Golem Capacity", String.valueOf(snapshot.ironGolemCapacity()), PresentationStyle.NEUTRAL, false));
        lines.add(new StatLine("Emerald Golems", String.valueOf(snapshot.emeraldGolemsPresent()), PresentationStyle.NEUTRAL, false));
        lines.add(new StatLine("Emerald Golem Capacity", String.valueOf(snapshot.emeraldGolemCapacity()), PresentationStyle.NEUTRAL, false));
        if (golemDifference >= 0) {
            lines.add(new StatLine(golemDifference > 0 ? "Iron Golem Room" : "Iron Golem Status",
                    golemDifference > 0 ? "+" + golemDifference : "At capacity",
                    golemDifference > 0 ? PresentationStyle.POSITIVE : PresentationStyle.WARNING, false));
        }
        lines.add(separator());

        boolean hasBank = !snapshot.bankName().isEmpty();
        lines.add(new StatLine("Bank", hasBank ? snapshot.bankName() : "None",
                hasBank ? PresentationStyle.INFRASTRUCTURE : PresentationStyle.NEGATIVE, false));
        return List.copyOf(lines);
    }

    /** Formats a cooldown in ticks without allowing a live cooldown to display 0:00. */
    public static String formatCooldownTicks(int ticks, int ticksPerSecond) {
        int safeTicks = Math.max(0, ticks);
        int totalSeconds = ticksPerSecond <= 0 ? 0 : (safeTicks + ticksPerSecond - 1) / ticksPerSecond;
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static StatLine separator() {
        return new StatLine("", "", PresentationStyle.NEUTRAL, true);
    }
}
