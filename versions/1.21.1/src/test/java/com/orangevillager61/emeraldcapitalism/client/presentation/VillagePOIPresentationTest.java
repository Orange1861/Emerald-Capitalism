package com.orangevillager61.emeraldcapitalism.client.presentation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VillagePOIPresentationTest {
    @Test
    void sortsByProfessionThenName() {
        List<VillagePOIPresentation.VillagerSnapshot> result = VillagePOIPresentation.sortVillagers(List.of(
                new VillagePOIPresentation.VillagerSnapshot("Zed", "Farmer", 20, 0, "none"),
                new VillagePOIPresentation.VillagerSnapshot("Ada", "Farmer", 20, 0, "none"),
                new VillagePOIPresentation.VillagerSnapshot("Bob", "Armorer", 20, 0, "none")
        ), VillagePOIPresentation.SortMode.PROFESSION_ASC);

        assertEquals(List.of("Bob", "Ada", "Zed"), result.stream()
                .map(VillagePOIPresentation.VillagerSnapshot::name).toList());
    }

    @Test
    void groupsJobSitesAndMarksInfrastructure() {
        List<VillagePOIPresentation.JobSiteRow> rows = VillagePOIPresentation.groupJobSites(List.of(
                new VillagePOIPresentation.JobSiteSnapshot("Bank", "1, 2, 3", true, "Ada"),
                new VillagePOIPresentation.JobSiteSnapshot("Farmer", "4, 5, 6", false, "—")
        ));

        assertEquals("Bank (1 claimed, 0 unclaimed)", rows.getFirst().text());
        assertEquals(PresentationStyle.INFRASTRUCTURE, rows.getFirst().style());
        assertEquals("Farmer (0 claimed, 1 unclaimed)", rows.get(2).text());
    }

    @Test
    void computesNonNegativeBedCapacityAndDeficit() {
        VillagePOIPresentation.BedCapacity capacity = VillagePOIPresentation.bedCapacity(5, 3, 4);

        assertEquals(0, capacity.availableBeds());
        assertEquals(-2, capacity.difference());
    }
}
