package com.orangevillager61.emeraldcapitalism.world.village;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VillageBuildingOrderTest {

    @Test
    void providerOrderUsesImportanceThenExpectedFootprintThenId() {
        List<VillageBuildingOrder.ProviderKey> providers = List.of(
                new VillageBuildingOrder.ProviderKey(VillageBuildingImportance.NORMAL, 900, "zeta"),
                new VillageBuildingOrder.ProviderKey(VillageBuildingImportance.IMPORTANT, 100, "small"),
                new VillageBuildingOrder.ProviderKey(VillageBuildingImportance.IMPORTANT, 900, "zeta"),
                new VillageBuildingOrder.ProviderKey(VillageBuildingImportance.IMPORTANT, 900, "alpha"));

        List<String> orderedIds = providers.stream()
                .sorted(VillageBuildingOrder.providerComparator())
                .map(VillageBuildingOrder.ProviderKey::providerId)
                .toList();

        assertEquals(List.of("alpha", "zeta", "small", "zeta"), orderedIds);
    }

    @Test
    void planOrderUsesImportanceAreaIdAndPositionAsTieBreakers() {
        List<VillageBuildingOrder.PlanKey> plans = List.of(
                new VillageBuildingOrder.PlanKey(VillageBuildingImportance.NORMAL, 999, "normal", 0, 0),
                new VillageBuildingOrder.PlanKey(VillageBuildingImportance.IMPORTANT, 100, "zeta", 0, 0),
                new VillageBuildingOrder.PlanKey(VillageBuildingImportance.IMPORTANT, 100, "alpha", 0, 0),
                new VillageBuildingOrder.PlanKey(VillageBuildingImportance.IMPORTANT, 100, "alpha", 1, 0),
                new VillageBuildingOrder.PlanKey(VillageBuildingImportance.IMPORTANT, 200, "small", 0, 0));

        List<String> orderedKeys = plans.stream()
                .sorted(VillageBuildingOrder.planComparator())
                .map(key -> key.providerId() + ":" + key.footprintArea()
                        + ":" + key.minX() + ":" + key.minZ())
                .toList();

        assertEquals(List.of("small:200:0:0", "alpha:100:0:0", "alpha:100:1:0",
                "zeta:100:0:0", "normal:999:0:0"), orderedKeys);
    }
}
