package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.test.MinecraftTestBootstrapExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MinecraftTestBootstrapExtension.class)
class VillageWorkCellTest {

    @Test
    void usesStableChunkAlignedCellsAndSeparateWorkCollections() {
        AABB bounds = new AABB(-8, 60, -8, 40, 80, 40);
        VillageRecord village = new VillageRecord(UUID.randomUUID(), BlockPos.ZERO, bounds);
        BlockPos repair = new BlockPos(-1, 64, -1);
        BlockPos crop = new BlockPos(17, 64, 17);

        village.addFarmland(repair);
        assertTrue(village.addToRepairQueue(repair));
        assertTrue(village.addMatureCropWork(crop));

        VillageWorkCell repairCell = village.getWorkCell(repair);
        VillageWorkCell cropCell = village.getWorkCell(crop);
        assertNotNull(repairCell);
        assertNotNull(cropCell);
        assertEquals(new VillageWorkCell.Key(-1, -1), repairCell.getKey());
        assertEquals(new VillageWorkCell.Key(1, 1), cropCell.getKey());
        assertEquals(1, repairCell.getPendingRepairCount());
        assertEquals(0, repairCell.getPendingCropWorkCount());
        assertEquals(0, cropCell.getPendingRepairCount());
        assertEquals(1, cropCell.getPendingCropWorkCount());
        assertEquals(2, village.getWorkCells().size());
        assertEquals(1, village.getPendingRepairCount());
        assertEquals(1, village.getPendingCropWorkCount());
        assertTrue(village.getRepairQueue().contains(repair));
        assertFalse(village.getRepairQueue().contains(crop));

        VillageWorkCell sameCell = village.getWorkCell(repair);
        village.setBoundingBox(new AABB(-8, 60, -8, 72, 80, 72));
        assertSame(sameCell, village.getWorkCell(repair));
    }

    @Test
    void rejectsOutOfBoundsQueuedPositionsWithoutMaterializingCells() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-8, 60, -8, 8, 80, 8));
        BlockPos outside = new BlockPos(32, 64, 32);

        village.addFarmland(outside);
        assertFalse(village.addToRepairQueue(outside));
        assertFalse(village.addMatureCropWork(outside));
        assertTrue(village.getRepairQueue().isEmpty());
        assertTrue(village.getMatureCropWorkQueue().isEmpty());
        assertTrue(village.getWorkCells().isEmpty());
    }

    @Test
    void matureCropInsertionIsIdempotentAndDoesNotDiscardRepairWork() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-8, 60, -8, 24, 80, 24));
        BlockPos repair = new BlockPos(3, 63, 3);
        BlockPos crop = new BlockPos(3, 64, 3);

        village.addFarmland(repair);
        assertTrue(village.addToRepairQueue(repair));
        assertTrue(village.addMatureCropWork(crop));
        assertFalse(village.addMatureCropWork(crop));
        assertTrue(village.removeMatureCropWork(crop));

        VillageWorkCell cell = village.getWorkCell(repair);
        assertNotNull(cell);
        assertEquals(1, cell.getPendingRepairCount());
        assertEquals(0, cell.getPendingCropWorkCount());
    }

    @Test
    void sharedCellRemainsMaterializedUntilBothCollectionsAreEmpty() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-8, 60, -8, 24, 80, 24));
        BlockPos repair = new BlockPos(3, 63, 3);
        BlockPos crop = new BlockPos(4, 64, 4);

        village.addFarmland(repair);
        assertTrue(village.addToRepairQueue(repair));
        assertTrue(village.addMatureCropWork(crop));

        village.removeFromRepairQueue(repair);
        assertFalse(village.getRepairQueue().contains(repair));
        VillageWorkCell cell = village.getWorkCell(crop);
        assertNotNull(cell);
        assertEquals(0, cell.getPendingRepairCount());
        assertEquals(1, cell.getPendingCropWorkCount());

        assertTrue(village.removeMatureCropWork(crop));
        assertTrue(village.getWorkCells().isEmpty());
    }

    @Test
    void shrinkingBoundsPrunesCanonicalQueuesAndTheirTransientCells() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-8, 60, -8, 40, 80, 40));
        BlockPos retainedRepair = new BlockPos(3, 63, 3);
        BlockPos removedCrop = new BlockPos(20, 64, 20);

        village.addFarmland(retainedRepair);
        assertTrue(village.addToRepairQueue(retainedRepair));
        assertTrue(village.addMatureCropWork(removedCrop));
        assertEquals(2, village.getWorkCells().size());

        village.setBoundingBox(new AABB(-8, 60, -8, 8, 80, 8));

        assertTrue(village.getRepairQueue().contains(retainedRepair));
        assertTrue(village.getMatureCropWorkQueue().isEmpty());
        assertEquals(1, village.getWorkCells().size());
        assertNotNull(village.getWorkCell(retainedRepair));
        assertTrue(village.getWorkCell(removedCrop) == null);
    }

    @Test
    void leasesExpireAndFailuresLeaveTheCellReadyForReassignment() {
        VillageWorkCell cell = new VillageWorkCell(new VillageWorkCell.Key(2, 3));
        BlockPos repair = new BlockPos(33, 64, 49);
        UUID farmer = UUID.randomUUID();

        assertTrue(cell.addFarmlandRepair(repair));
        cell.markWorkReady(10L);
        assertEquals(10L, cell.getWorkReadySince());
        assertTrue(cell.canLease(10L));

        cell.assignLease(farmer, 10L, 20L);
        assertEquals(farmer, cell.getActiveLease().farmerId());
        assertFalse(cell.canLease(29L));
        assertTrue(cell.renewLease(farmer, 29L, 20L));
        assertFalse(cell.releaseExpiredLease(48L));
        assertTrue(cell.releaseExpiredLease(49L));
        assertTrue(cell.canLease(49L));

        cell.assignLease(farmer, 49L, 20L);
        assertTrue(cell.recordLeaseFailure(farmer, 54L, 40L));
        assertEquals(1, cell.getLeaseFailureCount());
        assertEquals(94L, cell.getLeaseRetryAfter());
        assertFalse(cell.canLease(93L));
        assertTrue(cell.canLease(94L));
    }

    @Test
    void workCellsRoundTripWithoutMergingRepairAndCropWork() {
        VillageRecord source = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-8, 60, -8, 40, 80, 40));
        BlockPos repair = new BlockPos(3, 64, 3);
        BlockPos crop = new BlockPos(4, 64, 4);
        source.addFarmland(repair);
        source.addToRepairQueue(repair);
        source.addMatureCropWork(crop);

        CompoundTag encoded = (CompoundTag) VillageRecord.CODEC
                .encodeStart(NbtOps.INSTANCE, source)
                .result()
                .orElseThrow();
        assertTrue(encoded.contains("mature_crop_work_queue"));
        assertFalse(encoded.contains("work_cells"));
        VillageRecord restored = VillageRecord.CODEC.parse(NbtOps.INSTANCE, encoded)
                .result()
                .orElseThrow();

        VillageWorkCell cell = restored.getWorkCell(repair);
        assertNotNull(cell);
        assertEquals(1, cell.getPendingRepairCount());
        assertEquals(1, cell.getPendingCropWorkCount());
        assertTrue(cell.getFarmlandRepairPositions().contains(repair));
        assertTrue(cell.getMatureCropWorkPositions().contains(crop));
        assertEquals(1, restored.getRepairQueue().size());
        assertEquals(1, restored.getMatureCropWorkQueue().size());
    }
}
