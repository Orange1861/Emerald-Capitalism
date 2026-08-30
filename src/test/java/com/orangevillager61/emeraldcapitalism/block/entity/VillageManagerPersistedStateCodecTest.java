package com.orangevillager61.emeraldcapitalism.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageManagerPersistedStateCodecTest {

    private static final UUID VILLAGE_ID = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final BlockPos BANK_POS = new BlockPos(-29_999_984, -64, 29_999_984);

    @Test
    void allAbsentAndPresentCombinationsRoundTrip() {
        assertEquals(VillageManagerBlockEntity.PersistedState.empty(), roundTrip(
                VillageManagerBlockEntity.PersistedState.empty()));
        assertEquals(new VillageManagerBlockEntity.PersistedState(Optional.of(VILLAGE_ID), Optional.empty()),
                roundTrip(new VillageManagerBlockEntity.PersistedState(Optional.of(VILLAGE_ID), Optional.empty())));
        assertEquals(new VillageManagerBlockEntity.PersistedState(Optional.empty(), Optional.of(BANK_POS)),
                roundTrip(new VillageManagerBlockEntity.PersistedState(Optional.empty(), Optional.of(BANK_POS))));
        assertEquals(new VillageManagerBlockEntity.PersistedState(Optional.of(VILLAGE_ID), Optional.of(BANK_POS)),
                roundTrip(VillageManagerBlockEntity.PersistedState.from(VILLAGE_ID, BANK_POS)));
    }

    @Test
    void missingFieldsUseUnlinkedDefaults() {
        VillageManagerBlockEntity.PersistedState decoded = VillageManagerBlockEntity.PersistedState.CODEC
                .parse(NbtOps.INSTANCE, new CompoundTag())
                .result()
                .orElseThrow();

        assertEquals(VillageManagerBlockEntity.PersistedState.empty(), decoded);
    }

    @Test
    void malformedLinksAreRejectedWithoutThrowing() {
        CompoundTag malformedVillage = new CompoundTag();
        malformedVillage.putString("village_id", "not-a-uuid");
        var villageResult = assertDoesNotThrow(() -> VillageManagerBlockEntity.PersistedState.CODEC
                .parse(NbtOps.INSTANCE, malformedVillage));
        assertTrue(villageResult.error().isPresent(), "malformed village ID should be rejected");

        CompoundTag malformedBank = new CompoundTag();
        malformedBank.putString("bank_pos", "not-an-int-stream");
        var bankResult = assertDoesNotThrow(() -> VillageManagerBlockEntity.PersistedState.CODEC
                .parse(NbtOps.INSTANCE, malformedBank));
        assertTrue(bankResult.error().isPresent(), "malformed bank position should be rejected");
    }

    @Test
    void codecContainsOnlyDurableLinks() {
        CompoundTag encoded = (CompoundTag) VillageManagerBlockEntity.PersistedState.CODEC
                .encodeStart(NbtOps.INSTANCE,
                        VillageManagerBlockEntity.PersistedState.from(VILLAGE_ID, BANK_POS))
                .result()
                .orElseThrow();

        assertEquals(Set.of("village_id", "bank_pos"), encoded.getAllKeys());
    }

    private static VillageManagerBlockEntity.PersistedState roundTrip(
            VillageManagerBlockEntity.PersistedState source) {
        CompoundTag encoded = (CompoundTag) VillageManagerBlockEntity.PersistedState.CODEC
                .encodeStart(NbtOps.INSTANCE, source)
                .result()
                .orElseThrow();
        return VillageManagerBlockEntity.PersistedState.CODEC
                .parse(NbtOps.INSTANCE, encoded)
                .result()
                .orElseThrow();
    }
}
