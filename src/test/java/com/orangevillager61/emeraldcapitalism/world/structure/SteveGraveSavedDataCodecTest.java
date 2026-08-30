package com.orangevillager61.emeraldcapitalism.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SteveGraveSavedDataCodecTest {

    @Test
    void roundTripsResolvedAndPlacedState() {
        SteveGraveSavedData original = new SteveGraveSavedData();
        original.setSpawnAnchor(new BlockPos(12, 80, -34));
        original.setTarget(new BlockPos(20_000, 0, -30_000));
        original.markPlaced(new BlockPos(20_000, 176, -30_000));

        CompoundTag encoded = original.save(new CompoundTag(), null);
        SteveGraveSavedData restored = SteveGraveSavedData.load(encoded, null);

        assertEquals(new BlockPos(12, 0, -34), restored.spawnAnchor());
        assertEquals(new BlockPos(20_000, 0, -30_000), restored.target());
        assertEquals(new BlockPos(20_000, 176, -30_000), restored.placedOrigin());
        assertEquals(SteveGraveSavedData.PlacementState.PLACED, restored.placementState());
        assertEquals(true, restored.isPlaced());
    }

    @Test
    void malformedPlacedStateFallsBackToTargetFound() {
        SteveGraveSavedData original = new SteveGraveSavedData();
        original.setTarget(new BlockPos(20_000, 0, -30_000));
        original.markPlaced(new BlockPos(20_000, 176, -30_000));
        CompoundTag encoded = original.save(new CompoundTag(), null);
        encoded.remove("placed_origin");

        SteveGraveSavedData restored = SteveGraveSavedData.load(encoded, null);

        assertFalse(restored.isPlaced());
        assertNull(restored.placedOrigin());
        assertEquals(SteveGraveSavedData.PlacementState.TARGET_FOUND, restored.placementState());
        assertEquals(new BlockPos(20_000, 0, -30_000), restored.target());
    }
}
