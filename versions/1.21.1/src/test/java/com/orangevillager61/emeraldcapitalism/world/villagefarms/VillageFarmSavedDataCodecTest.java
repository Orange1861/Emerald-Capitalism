package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageFarmSavedDataCodecTest {

    @Test
    void codecRoundTripPreservesIndependentPositionSets() {
        VillageFarmSavedData original = new VillageFarmSavedData();
        BlockPos origin = new BlockPos(0, 0, 0);
        BlockPos negative = new BlockPos(-31, -64, -17);
        BlockPos large = new BlockPos(30_000_000, 319, -30_000_000);
        BlockPos overlap = new BlockPos(4, 5, 6);

        original.markVillageDetected(origin);
        original.markVillageDetected(negative);
        original.markVillageDetected(overlap);
        original.markFarmsPlaced(large);
        original.markFarmsPlaced(overlap);

        VillageFarmSavedData restored = VillageFarmSavedData.CODEC.parse(NbtOps.INSTANCE,
                        VillageFarmSavedData.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow())
                .result().orElseThrow();
        assertEquals(Set.of(origin, negative, overlap), restored.getDetectedVillages());
        assertEquals(Set.of(large, overlap), restored.getFarmsPlacedVillages());
        assertTrue(restored.getDetectedVillages().contains(overlap));
        assertTrue(restored.getFarmsPlacedVillages().contains(overlap));
    }

    @Test
    void currentSavedDataNbtAdapterRoundTripsBothSets() {
        VillageFarmSavedData original = new VillageFarmSavedData();
        BlockPos detected = new BlockPos(-100, 20, 200);
        BlockPos placed = new BlockPos(100, -20, -200);
        original.markVillageDetected(detected);
        original.markFarmsPlaced(placed);

        CompoundTag saved = original.save(new CompoundTag(), null);
        VillageFarmSavedData restored = VillageFarmSavedData.load(saved, null);

        assertTrue(restored.isVillageDetected(detected));
        assertTrue(restored.areFarmsPlaced(placed));
        assertFalse(restored.areFarmsPlaced(detected));
    }

    @Test
    void duplicateMarksAndViewsPreserveDirtyAndIsolationBehavior() {
        VillageFarmSavedData data = new VillageFarmSavedData();
        BlockPos center = new BlockPos(1, 2, 3);

        assertTrue(data.markVillageDetected(center));
        assertTrue(data.isDirty());
        data.setDirty(false);
        assertFalse(data.markVillageDetected(center));
        assertFalse(data.isDirty());

        assertTrue(data.markFarmsPlaced(center));
        assertTrue(data.isDirty());
        data.setDirty(false);
        assertFalse(data.markFarmsPlaced(center));
        assertFalse(data.isDirty());

        assertThrows(UnsupportedOperationException.class,
                () -> data.getDetectedVillages().add(new BlockPos(9, 9, 9)));
        assertThrows(UnsupportedOperationException.class,
                () -> data.getFarmsPlacedVillages().clear());
    }

    @Test
    void oversizedPersistedPositionSetIsRejectedAndPartialRecoveryRemainsBounded() {
        Tag encodedPosition = BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, BlockPos.ZERO)
                .result().orElseThrow();
        ListTag positions = new ListTag();
        for (int i = 0; i <= VillageFarmSavedData.MAX_PERSISTED_VILLAGE_POSITIONS; i++) {
            positions.add(encodedPosition.copy());
        }
        CompoundTag root = new CompoundTag();
        root.put("detected_villages", positions);

        assertTrue(VillageFarmSavedData.CODEC.parse(NbtOps.INSTANCE, root).error().isPresent());
        assertTrue(VillageFarmSavedData.load(root, null).getDetectedVillages().size()
                <= VillageFarmSavedData.MAX_PERSISTED_VILLAGE_POSITIONS);
    }
}
