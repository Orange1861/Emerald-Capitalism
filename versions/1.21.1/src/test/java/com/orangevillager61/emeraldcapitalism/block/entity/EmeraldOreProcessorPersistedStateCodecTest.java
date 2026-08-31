package com.orangevillager61.emeraldcapitalism.block.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmeraldOreProcessorPersistedStateCodecTest {

    @Test
    void codecRoundTripPreservesEmptyActiveAndCompletedTimerStates() {
        assertEquals(new EmeraldOreProcessorBlockEntity.PersistedState(0, 0, 0, 400),
                roundTrip(EmeraldOreProcessorBlockEntity.PersistedState.empty()));
        assertEquals(new EmeraldOreProcessorBlockEntity.PersistedState(211, 400, 137, 400),
                roundTrip(new EmeraldOreProcessorBlockEntity.PersistedState(211, 400, 137, 400)));
        assertEquals(new EmeraldOreProcessorBlockEntity.PersistedState(0, 400, 0, 400),
                roundTrip(new EmeraldOreProcessorBlockEntity.PersistedState(0, 400, 0, 400)));
    }

    @Test
    void missingTimerFieldsUseSafeDefaults() {
        EmeraldOreProcessorBlockEntity.PersistedState decoded = EmeraldOreProcessorBlockEntity.PersistedState.CODEC
                .parse(NbtOps.INSTANCE, new CompoundTag())
                .result()
                .orElseThrow();

        assertEquals(EmeraldOreProcessorBlockEntity.PersistedState.empty(), decoded);
    }

    @Test
    void negativeAndImpossibleTimersAreRejectedWithoutThrowing() {
        CompoundTag negative = new CompoundTag();
        negative.putInt("burn_time", -1);
        assertCodecError(negative);

        CompoundTag exceedsBurnDuration = new CompoundTag();
        exceedsBurnDuration.putInt("burn_time", 2);
        exceedsBurnDuration.putInt("burn_duration", 1);
        assertCodecError(exceedsBurnDuration);

        CompoundTag completedProgress = new CompoundTag();
        completedProgress.putInt("cook_progress", 400);
        completedProgress.putInt("cook_total_time", 400);
        assertCodecError(completedProgress);
    }

    @Test
    void codecContainsOnlyDurableTimersAndNoTransientTickingState() {
        CompoundTag encoded = (CompoundTag) EmeraldOreProcessorBlockEntity.PersistedState.CODEC
                .encodeStart(NbtOps.INSTANCE,
                        new EmeraldOreProcessorBlockEntity.PersistedState(3, 400, 12, 399))
                .result()
                .orElseThrow();

        assertEquals(Set.of("burn_time", "burn_duration", "cook_progress", "cook_total_time"),
                encoded.getAllKeys());
        assertFalse(encoded.contains("lit"));
        assertFalse(encoded.contains("ticking"));
        assertTrue(encoded.contains("cook_progress"));
    }

    private static EmeraldOreProcessorBlockEntity.PersistedState roundTrip(
            EmeraldOreProcessorBlockEntity.PersistedState source) {
        CompoundTag encoded = (CompoundTag) EmeraldOreProcessorBlockEntity.PersistedState.CODEC
                .encodeStart(NbtOps.INSTANCE, source)
                .result()
                .orElseThrow();
        return EmeraldOreProcessorBlockEntity.PersistedState.CODEC
                .parse(NbtOps.INSTANCE, encoded)
                .result()
                .orElseThrow();
    }

    private static void assertCodecError(CompoundTag tag) {
        var result = EmeraldOreProcessorBlockEntity.PersistedState.CODEC.parse(NbtOps.INSTANCE, tag);
        assertTrue(result.error().isPresent(), "invalid timer state should be rejected");
    }
}
