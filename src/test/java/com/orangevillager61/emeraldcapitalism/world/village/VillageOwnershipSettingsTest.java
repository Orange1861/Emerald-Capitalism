package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageOwnershipSettingsTest {

    @Test
    void repairSettingsRoundTripThroughVillageCodec() {
        VillageRecord source = new VillageRecord(
                UUID.randomUUID(), new BlockPos(0, 64, 0),
                new AABB(-8, 60, -8, 8, 80, 8));
        source.setFarmlandRepairEnabled(false);
        source.setDoorRepairEnabled(false);

        CompoundTag encoded = (CompoundTag) VillageRecord.CODEC
                .encodeStart(NbtOps.INSTANCE, source)
                .result()
                .orElseThrow();
        VillageRecord decoded = VillageRecord.CODEC
                .parse(NbtOps.INSTANCE, encoded)
                .result()
                .orElseThrow();

        assertFalse(decoded.isFarmlandRepairEnabled());
        assertFalse(decoded.isDoorRepairEnabled());
    }

    @Test
    void disablingDoorRepairClearsTheDerivedDoorCache() {
        VillageRecord record = new VillageRecord(
                UUID.randomUUID(), new BlockPos(0, 64, 0),
                new AABB(-8, 60, -8, 8, 80, 8));
        BlockPos door = new BlockPos(1, 64, 1);
        record.addDoor(door);

        assertTrue(record.getDoorRegistry().contains(door));
        record.setDoorRepairEnabled(false);
        assertTrue(record.getDoorRegistry().isEmpty());
    }

    @Test
    void destroyedTrackedDoorBecomesARepairTargetAndPlacementClearsIt() {
        VillageRecord record = new VillageRecord(
                UUID.randomUUID(), new BlockPos(0, 64, 0),
                new AABB(-8, 60, -8, 8, 80, 8));
        BlockPos door = new BlockPos(1, 64, 1);
        record.addDoor(door);

        assertTrue(record.markDoorMissing(door));
        assertTrue(record.getDoorRegistry().isEmpty());
        assertTrue(record.getMissingDoorRegistry().contains(door));

        assertTrue(record.markDoorRepaired(door));
        assertTrue(record.getDoorRegistry().contains(door));
        assertTrue(record.getMissingDoorRegistry().isEmpty());
    }

    @Test
    void missingDoorTargetsSurviveCodecRoundTrip() {
        VillageRecord source = new VillageRecord(
                UUID.randomUUID(), new BlockPos(0, 64, 0),
                new AABB(-8, 60, -8, 8, 80, 8));
        BlockPos door = new BlockPos(1, 64, 1);
        source.addDoor(door);
        source.markDoorMissing(door);

        CompoundTag encoded = (CompoundTag) VillageRecord.CODEC
                .encodeStart(NbtOps.INSTANCE, source)
                .result()
                .orElseThrow();
        VillageRecord decoded = VillageRecord.CODEC
                .parse(NbtOps.INSTANCE, encoded)
                .result()
                .orElseThrow();

        assertTrue(decoded.getMissingDoorRegistry().contains(door));
    }
}
