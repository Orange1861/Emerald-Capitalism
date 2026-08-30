package com.orangevillager61.emeraldcapitalism.world.village.naming.worldgen;

import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignal;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignalSnapshot;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldgenVillageSignalExtractorTest {

    @Test
    void fixtureSnapshotRequiresEveryNormalizedSignal() {
        VillageSignalSnapshot.Builder builder = VillageSignalSnapshot.builder();
        for (VillageSignal signal : VillageSignal.values()) {
            builder.with(signal, 0.5);
        }

        VillageSignalSnapshot snapshot = WorldgenVillageSignalExtractor.validateSnapshot(builder.build());

        assertEquals(EnumSet.allOf(VillageSignal.class), snapshot.all().keySet());
    }

    @Test
    void unimplementedWorldgenSignalsRemainExplicit() {
        assertEquals(Set.of(
                        VillageSignal.HOSTILE_STRUCTURE_NEARBY,
                        VillageSignal.USEFUL_STRUCTURE_NEARBY,
                        VillageSignal.DANGEROUS_RUIN_PROXIMITY,
                        VillageSignal.MEMORY_STRUCTURE_PROXIMITY),
                WorldgenVillageSignalExtractor.explicitlyUnimplementedSignals());
    }

    @Test
    void fixtureRejectsUnnormalizedValues() {
        EnumMap<VillageSignal, Double> values = new EnumMap<>(VillageSignal.class);
        for (VillageSignal signal : VillageSignal.values()) {
            values.put(signal, 0.0);
        }
        values.put(VillageSignal.VILLAGER_COUNT, 1.1);

        VillageSignalSnapshot.Builder builder = VillageSignalSnapshot.builder();
        values.forEach((signal, value) -> builder.with(signal, value));
        assertThrows(IllegalStateException.class,
                () -> WorldgenVillageSignalExtractor.validateSnapshot(builder.build()));
    }
}
