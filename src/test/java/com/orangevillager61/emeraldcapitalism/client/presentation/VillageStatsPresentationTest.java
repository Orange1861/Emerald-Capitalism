package com.orangevillager61.emeraldcapitalism.client.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VillageStatsPresentationTest {
    @Test
    void exposesBedDeficitAsNegativeAndClampsHealthyFarmland() {
        var lines = VillageStatsPresentation.lines(new VillageStatsPresentation.Snapshot(
                -2, "none", "none", 5, 3, 4, 2, 4,
                1, 0, 0, 0, ""));

        assertEquals(PresentationStyle.NEGATIVE, lines.stream()
                .filter(line -> line.label().equals("Bed Deficit")).findFirst().orElseThrow().style());
        assertEquals("0", lines.stream()
                .filter(line -> line.label().equals("Healthy")).findFirst().orElseThrow().value());
    }

    @Test
    void formatsCooldownIndependentValuesAsSemanticRows() {
        var lines = VillageStatsPresentation.lines(new VillageStatsPresentation.Snapshot(
                0, "none", "none", 2, 2, 2, 4, 0,
                1, 1, 1, 1, "Central Bank"));

        assertEquals(PresentationStyle.INFRASTRUCTURE, lines.getLast().style());
    }

    @Test
    void exposesTrackedDoorCount() {
        var lines = VillageStatsPresentation.lines(new VillageStatsPresentation.Snapshot(
                0, "none", "none", 2, 2, 2, 4, 7, 0, 1, 1, 1, 1, ""));

        assertEquals("7", lines.stream()
                .filter(line -> line.label().equals("Doors")).findFirst().orElseThrow().value());
    }

    @Test
    void exposesVillageColor() {
        var lines = VillageStatsPresentation.lines(new VillageStatsPresentation.Snapshot(
                0, "none", "none", 1, 1, 1, 0, 0, 0,
                0, 0, 0, 0, "Pink", ""));

        assertEquals("Pink", lines.stream()
                .filter(line -> line.label().equals("Village Color")).findFirst().orElseThrow().value());
    }

    @Test
    void hidesVillageIdentityAndIronGolemOverCapacityRows() {
        var lines = VillageStatsPresentation.lines(new VillageStatsPresentation.Snapshot(
                0, "hidden-id", "hidden-bell", 12, 12, 12, 4, 0,
                1, 2, 0, 0, 0, ""));

        assertFalse(lines.stream().anyMatch(line -> line.label().equals("Village ID")));
        assertFalse(lines.stream().anyMatch(line -> line.label().equals("Bell Position")));
        assertFalse(lines.stream().anyMatch(line -> line.label().equals("Iron Golem Over Capacity")));
    }

    @Test
    void roundsLiveCooldownsUpToTheNextSecond() {
        assertEquals("0:01", VillageStatsPresentation.formatCooldownTicks(1, 20));
        assertEquals("1:01", VillageStatsPresentation.formatCooldownTicks(1201, 20));
        assertEquals("0:00", VillageStatsPresentation.formatCooldownTicks(-1, 20));
    }
}
