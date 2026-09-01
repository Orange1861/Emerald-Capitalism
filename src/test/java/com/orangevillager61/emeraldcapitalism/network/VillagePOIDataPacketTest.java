package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.world.village.JobSiteEntry;
import com.orangevillager61.emeraldcapitalism.world.village.VillageColor;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagePOIDataPacketTest {

    @AfterEach
    void clearCache() {
        VillagePOIClientCache.clear();
    }

    @Test
    void roundTripPreservesEveryGroupedFieldAndRelationship() {
        UUID villageId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        VillagerPOIRecord villager = new VillagerPOIRecord(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "Ada", "Librarian", new BlockPos(1, 64, 2), null,
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                17.5F, VillagerPOIRecord.Status.DEPARTED, 7, 1234L);
        villager.setOpinionOfPlayer(23);
        JobSiteEntry jobSite = new JobSiteEntry(new BlockPos(8, 65, 9), "Librarian", true);
        VillagePOIDataPacket source = new VillagePOIDataPacket(
                true,
                new VillagePOIDataPacket.Status(true, false),
                new VillagePOIDataPacket.Identity(villageId, "Test Village", true,
                        new BlockPos(0, 64, 0), VillageColor.PINK),
                List.of(villager),
                new VillagePOIDataPacket.Totals(12, 5, List.of(jobSite),
                        List.of(new BlockPos(1, 63, 2))),
                new VillagePOIDataPacket.RepairData(4, 3, 2, false, true,
                        List.of(new BlockPos(10, 64, 10))),
                new VillagePOIDataPacket.EntityCounts(6, 2, 3, 7),
                new VillagePOIDataPacket.RelationshipData(101,
                        VillageRelationship.GOVERNOR_CANDIDATE, true),
                new VillagePOIDataPacket.Bounds(-1.0, 60.0, -2.0, 20.0, 80.0, 30.0),
                new VillagePOIDataPacket.Messages("Welcome", "Village Bank"));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        VillagePOIDataPacket.STREAM_CODEC.encode(buffer, source);
        VillagePOIDataPacket decoded = VillagePOIDataPacket.STREAM_CODEC.decode(buffer);

        assertEquals(source.hasData(), decoded.hasData());
        assertEquals(source.status(), decoded.status());
        assertEquals(source.identity(), decoded.identity());
        assertEquals(source.totals(), decoded.totals());
        assertEquals(source.repair(), decoded.repair());
        assertEquals(source.entityCounts(), decoded.entityCounts());
        assertEquals(source.relationshipData(), decoded.relationshipData());
        assertEquals(source.bounds(), decoded.bounds());
        assertEquals(source.messages(), decoded.messages());
        assertEquals(1, decoded.records().size());
        VillagerPOIRecord decodedVillager = decoded.records().get(0);
        assertEquals(villager.getVillagerUUID(), decodedVillager.getVillagerUUID());
        assertEquals(villager.getDisplayName(), decodedVillager.getDisplayName());
        assertEquals(villager.getProfession(), decodedVillager.getProfession());
        assertEquals(villager.getBedPos(), decodedVillager.getBedPos());
        assertEquals(villager.getJobSitePos(), decodedVillager.getJobSitePos());
        assertEquals(villager.getFamilyId(), decodedVillager.getFamilyId());
        assertEquals(villager.getHealth(), decodedVillager.getHealth());
        assertEquals(villager.getOpinionOfPlayer(), decodedVillager.getOpinionOfPlayer());
        assertEquals(villager.getStatus(), decodedVillager.getStatus());
        assertEquals(villager.getDepartureCounter(), decodedVillager.getDepartureCounter());
        assertEquals(villager.getLastVerifiedTick(), decodedVillager.getLastVerifiedTick());
    }

    @Test
    void rejectsNegativeOrExcessiveListCountsBeforeAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> VillagePOIDataPacket.STREAM_CODEC.decode(poiPrefix(-1, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> VillagePOIDataPacket.STREAM_CODEC.decode(poiPrefix(0,
                        VillagePOIDataPacket.MAX_JOB_SITES + 1, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> VillagePOIDataPacket.STREAM_CODEC.decode(poiPrefix(0, 0,
                        VillagePOIDataPacket.MAX_BED_POSITIONS + 1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> VillagePOIDataPacket.STREAM_CODEC.decode(poiPrefix(0, 0, 0,
                        VillagePOIDataPacket.MAX_REPAIR_QUEUE_POSITIONS + 1)));
    }

    @Test
    void truncatedPayloadIsRejectedAndDoesNotReplaceExistingCache() {
        UUID villageId = UUID.randomUUID();
        VillagePOIDataPacket source = new VillagePOIDataPacket(
                true, new VillagePOIDataPacket.Status(false, true),
                new VillagePOIDataPacket.Identity(villageId, "Stable", false, BlockPos.ZERO),
                List.of(), new VillagePOIDataPacket.Totals(0, 0, List.of(), List.of()),
                new VillagePOIDataPacket.RepairData(0, 0, 0, true, true, List.of()),
                new VillagePOIDataPacket.EntityCounts(0, 0, 0, 0),
                new VillagePOIDataPacket.RelationshipData(0, VillageRelationship.NEUTRAL, false),
                new VillagePOIDataPacket.Bounds(0, 0, 0, 1, 1, 1),
                new VillagePOIDataPacket.Messages("", ""));
        VillagePOIClientCache.update(source);

        assertThrows(IndexOutOfBoundsException.class,
                () -> VillagePOIDataPacket.STREAM_CODEC.decode(new FriendlyByteBuf(Unpooled.buffer())));
        assertEquals(villageId, VillagePOIClientCache.getVillageId());
        assertTrue(VillagePOIClientCache.hasData());
    }

    @Test
    void clearingCacheDisablesRepairSettings() {
        VillagePOIDataPacket source = new VillagePOIDataPacket(
                true, new VillagePOIDataPacket.Status(false, true),
                new VillagePOIDataPacket.Identity(UUID.randomUUID(), "Stable", false, BlockPos.ZERO),
                List.of(), new VillagePOIDataPacket.Totals(0, 0, List.of(), List.of()),
                new VillagePOIDataPacket.RepairData(0, 0, 0, true, true, List.of()),
                new VillagePOIDataPacket.EntityCounts(0, 0, 0, 0),
                new VillagePOIDataPacket.RelationshipData(0, VillageRelationship.NEUTRAL, false),
                new VillagePOIDataPacket.Bounds(0, 0, 0, 1, 1, 1),
                new VillagePOIDataPacket.Messages("", ""));
        VillagePOIClientCache.update(source);

        VillagePOIClientCache.clear();

        assertFalse(VillagePOIClientCache.hasData());
        assertFalse(VillagePOIClientCache.isFarmlandRepairEnabled());
        assertFalse(VillagePOIClientCache.isDoorRepairEnabled());
    }

    private static FriendlyByteBuf poiPrefix(int recordCount, int jobSiteCount,
                                             int bedPositionCount, int repairQueueCount) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBoolean(true);
        buffer.writeBoolean(false);
        buffer.writeBoolean(true);
        buffer.writeUUID(UUID.randomUUID());
        buffer.writeUtf("Village", ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
        buffer.writeBoolean(false);
        buffer.writeBlockPos(BlockPos.ZERO);
        buffer.writeVarInt(VillageColor.RED.networkId());
        buffer.writeVarInt(recordCount);
        if (recordCount != 0) {
            return buffer;
        }
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeVarInt(jobSiteCount);
        if (jobSiteCount != 0) {
            return buffer;
        }
        buffer.writeVarInt(bedPositionCount);
        if (bedPositionCount != 0) {
            return buffer;
        }
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeBoolean(false);
        buffer.writeBoolean(false);
        buffer.writeVarInt(repairQueueCount);
        return buffer;
    }
}
