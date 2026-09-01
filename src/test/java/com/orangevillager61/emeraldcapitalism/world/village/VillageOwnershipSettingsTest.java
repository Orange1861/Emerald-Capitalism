package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        record.addDoor(door, testDoorState());

        assertTrue(record.getDoorRegistry().contains(door));
        record.setDoorRepairEnabled(false);
        assertTrue(record.getDoorRegistry().isEmpty());
        assertNull(record.getDoorPlacement(door));
    }

    @Test
    void destroyedTrackedDoorBecomesARepairTargetAndPlacementClearsIt() {
        VillageRecord record = new VillageRecord(
                UUID.randomUUID(), new BlockPos(0, 64, 0),
                new AABB(-8, 60, -8, 8, 80, 8));
        BlockPos door = new BlockPos(1, 64, 1);
        record.addDoor(door, testDoorState());

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
        source.addDoor(door, testDoorState());
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
        assertEquals(new VillageRecord.DoorPlacement(Direction.WEST, DoorHingeSide.RIGHT),
                decoded.getDoorPlacement(door));
    }

    private static BlockState testDoorState() {
        return Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.WEST)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
    }
}
