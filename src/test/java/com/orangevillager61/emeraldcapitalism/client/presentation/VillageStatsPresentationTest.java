package com.orangevillager61.emeraldcapitalism.client.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void roundsLiveCooldownsUpToTheNextSecond() {
        assertEquals("0:01", VillageStatsPresentation.formatCooldownTicks(1, 20));
        assertEquals("1:01", VillageStatsPresentation.formatCooldownTicks(1201, 20));
        assertEquals("0:00", VillageStatsPresentation.formatCooldownTicks(-1, 20));
    }
}
