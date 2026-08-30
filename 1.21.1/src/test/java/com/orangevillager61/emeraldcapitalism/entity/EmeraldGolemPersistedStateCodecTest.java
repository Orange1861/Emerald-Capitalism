package com.orangevillager61.emeraldcapitalism.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmeraldGolemPersistedStateCodecTest {

    @Test
    void absentBankEmployeePositionRoundTripsAsUnassigned() {
        EmeraldGolem.PersistedState decoded = roundTrip(EmeraldGolem.PersistedState.empty());

        assertTrue(decoded.bankEmployeePos().isEmpty());
    }

    @Test
    void presentBankEmployeePositionRoundTripsNegativeAndLargeLegalCoordinates() {
        BlockPos bankPosition = new BlockPos(-29_999_984, -64, 29_999_984);

        EmeraldGolem.PersistedState decoded = roundTrip(
                EmeraldGolem.PersistedState.from(bankPosition, false, null, 0));

        assertEquals(Optional.of(bankPosition), decoded.bankEmployeePos());
    }

    @Test
    void ambushMarkerRoundTripsAlongsideBankState() {
        EmeraldGolem.PersistedState decoded = roundTrip(
                EmeraldGolem.PersistedState.from(new BlockPos(12, 64, -8), true, null, 0));

        assertTrue(decoded.ambush());
        assertEquals(Optional.of(new BlockPos(12, 64, -8)), decoded.bankEmployeePos());
    }

    @Test
    void missingPositionUsesEmptyDefaultAndMalformedPositionReturnsControlledError() {
        EmeraldGolem.PersistedState missing = EmeraldGolem.PersistedState.CODEC
                .parse(NbtOps.INSTANCE, new CompoundTag())
                .result().orElseThrow();
        assertTrue(missing.bankEmployeePos().isEmpty());
        assertTrue(!missing.ambush());

        CompoundTag malformed = new CompoundTag();
        malformed.putString("bank_employee_pos", "not-an-int-stream");

        var result = assertDoesNotThrow(() -> EmeraldGolem.PersistedState.CODEC
                .parse(NbtOps.INSTANCE, malformed));
        assertTrue(result.error().isPresent(), "malformed position should be rejected by the codec");
    }

    private static EmeraldGolem.PersistedState roundTrip(EmeraldGolem.PersistedState source) {
        CompoundTag encoded = (CompoundTag) EmeraldGolem.PersistedState.CODEC
                .encodeStart(NbtOps.INSTANCE, source)
                .result()
                .orElseThrow();
        return EmeraldGolem.PersistedState.CODEC.parse(NbtOps.INSTANCE, encoded)
                .result()
                .orElseThrow();
    }
}
