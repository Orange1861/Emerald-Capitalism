package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageRegistryDataCodecTest {

    @Test
    void persistsDriftRulesInAscendingRuleOrder() {
        VillageRecord original = new VillageRecord(
                UUID.randomUUID(), new BlockPos(0, 64, 0), new AABB(-1, 63, -1, 1, 65, 1));
        assertTrue(original.setVillagerNamingState("plains", List.of("D9", "D3")));
        assertEquals(List.of("D3", "D9"), original.getVillagerNamingDriftRules());

        VillageRecord restored = VillageRecord.CODEC.parse(
                        NbtOps.INSTANCE,
                        VillageRecord.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow())
                .result().orElseThrow();
        assertEquals(List.of("D3", "D9"), restored.getVillagerNamingDriftRules());
    }

    @Test
    void codecRoundTripPreservesDurableRegistryStateAndClearsTransientState() {
        VillageRegistryData original = populatedRegistry();

        VillageRegistryData restored = VillageRegistryData.CODEC.parse(NbtOps.INSTANCE,
                        VillageRegistryData.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow())
                .result().orElseThrow();

        UUID firstId = UUID.nameUUIDFromBytes("l4-first".getBytes());
        UUID secondId = UUID.nameUUIDFromBytes("l4-second".getBytes());
        VillageRecord first = restored.getVillages().get(firstId);
        VillageRecord second = restored.getVillages().get(secondId);
        assertEquals(2, restored.getVillages().size());
        assertEquals("First Village", first.getName());
        assertEquals("Second Village", second.getName());
        assertEquals(new BlockPos(-10, 70, 12), first.getBellPosition());
        assertEquals(new AABB(-20.5, 60.0, 2.5, 4.5, 90.0, 24.5), first.getBoundingBox());
        assertEquals(1, first.getMembers().size());
        VillagerPOIRecord member = first.getMembers().values().iterator().next();
        assertEquals("Codec Villager", member.getDisplayName());
        assertEquals(new BlockPos(-8, 70, 12), member.getBedPos());
        assertEquals(VillagerPOIRecord.Status.ACTIVE, member.getStatus());
        assertEquals(7, first.getOpinionModifier(UUID.nameUUIDFromBytes("player".getBytes())));
        assertTrue(first.getFarmlandRegistry().contains(new BlockPos(-2, 70, 3)));
        assertTrue(first.getRepairQueue().contains(new BlockPos(-2, 70, 3)));
        assertTrue(first.getDoorRegistry().contains(new BlockPos(-4, 70, 3)));
        assertEquals(new VillageRecord.DoorPlacement(Direction.SOUTH, DoorHingeSide.RIGHT),
                first.getDoorPlacement(new BlockPos(-4, 70, 3)));
        assertTrue(first.getClaimedPositions().isEmpty());
        assertNull(restored.getVMPos(firstId));

        assertTrue(restored.isVillageRegistered(new ChunkPos(-3, 4)));
        assertTrue(restored.hasGeneratedBank(secondId));
        assertTrue(restored.hasGeneratedLibrary(secondId));
        assertTrue(restored.hasGeneratedLumbermill(secondId));
        assertTrue(restored.hasGeneratedLumbermillStructure(123456789L));
        assertEquals(List.of(new BlockPos(40, 64, -12)), restored.getAbandonedVaultPositions());
        assertEquals(new BlockPos(100, 65, -100), restored.getBankPos(secondId));
        assertEquals(1, restored.getPendingManagerPlacements().size());
        BoundingBox pending = restored.getPendingManagerPlacements().get(0).structureBox();
        assertEquals(-8, pending.minX());
        assertEquals(10, pending.minY());
        assertEquals(-6, pending.minZ());
        assertEquals(12, pending.maxX());
        assertEquals(22, pending.maxY());
        assertEquals(14, pending.maxZ());

        VillageRecord third = new VillageRecord(
                UUID.randomUUID(), new BlockPos(0, 64, 0), new AABB(-1, 63, -1, 1, 65, 1));
        restored.assignLegacyVillageNumberName(third);
        assertEquals("Village 3", third.getName());
    }

    @Test
    void currentSavedDataNbtAdapterRoundTripsRegistryState() {
        VillageRegistryData original = populatedRegistry();
        CompoundTag saved = original.save(new CompoundTag(), null);

        VillageRegistryData restored = VillageRegistryData.load(saved, null);
        UUID firstId = UUID.nameUUIDFromBytes("l4-first".getBytes());
        assertEquals("First Village", restored.getVillages().get(firstId).getName());
        assertEquals(1, restored.getPendingManagerPlacements().size());
        assertNull(restored.getVMPos(firstId));
    }

    @Test
    void invertedPendingBoundsNormalizeAndMalformedNestedVillageIsSkipped() {
        CompoundTag root = new CompoundTag();
        ListTag villages = new ListTag();
        CompoundTag malformed = new CompoundTag();
        malformed.putString("name", "missing required identity");
        villages.add(malformed);
        CompoundTag invalidBounds = (CompoundTag) VillageRecord.CODEC.encodeStart(
                        NbtOps.INSTANCE,
                        new VillageRecord(UUID.randomUUID(), new BlockPos(0, 64, 0),
                                new AABB(-1, 63, -1, 1, 65, 1)))
                .result().orElseThrow();
        invalidBounds.getCompound("bounding_box").putDouble("min_x", Double.NaN);
        villages.add(invalidBounds);
        root.put("villages", villages);

        ListTag pending = new ListTag();
        CompoundTag inverted = new CompoundTag();
        inverted.putUUID("village_id", UUID.randomUUID());
        inverted.putInt("min_x", 10);
        inverted.putInt("min_y", 20);
        inverted.putInt("min_z", 30);
        inverted.putInt("max_x", -10);
        inverted.putInt("max_y", -20);
        inverted.putInt("max_z", -30);
        pending.add(inverted);
        root.put("pending_manager_placements", pending);

        VillageRegistryData restored = assertDoesNotThrow(() -> VillageRegistryData.load(root, null));
        assertTrue(restored.getVillages().isEmpty());
        assertEquals(1, restored.getPendingManagerPlacements().size());
        BoundingBox normalized = restored.getPendingManagerPlacements().get(0).structureBox();
        assertEquals(-10, normalized.minX());
        assertEquals(-20, normalized.minY());
        assertEquals(-30, normalized.minZ());
        assertEquals(10, normalized.maxX());
        assertEquals(20, normalized.maxY());
        assertEquals(30, normalized.maxZ());

        assertTrue(restored.getVillages().isEmpty(), "malformed identity and non-finite bounds are skipped");

        CompoundTag invalidUuidRoot = new CompoundTag();
        ListTag invalidUuidPending = new ListTag();
        CompoundTag invalidUuid = new CompoundTag();
        invalidUuid.put("village_id", new CompoundTag());
        invalidUuid.putInt("min_x", 0);
        invalidUuid.putInt("min_y", 0);
        invalidUuid.putInt("min_z", 0);
        invalidUuid.putInt("max_x", 1);
        invalidUuid.putInt("max_y", 1);
        invalidUuid.putInt("max_z", 1);
        invalidUuidPending.add(invalidUuid);
        invalidUuidRoot.put("pending_manager_placements", invalidUuidPending);
        VillageRegistryData invalidUuidData = assertDoesNotThrow(
                () -> VillageRegistryData.load(invalidUuidRoot, null));
        assertTrue(invalidUuidData.getPendingManagerPlacements().isEmpty());
    }

    @Test
    void duplicatePersistedVillageMembersAndOpinionIdsKeepTheFirstEntry() {
        CompoundTag village = encodedMinimalVillage();

        CompoundTag member = encodedMember();
        ListTag members = new ListTag();
        members.add(member);
        members.add(member.copy());
        village.put("members", members);

        UUID playerId = UUID.randomUUID();
        ListTag opinions = new ListTag();
        CompoundTag firstOpinion = new CompoundTag();
        firstOpinion.putUUID("player_id", playerId);
        firstOpinion.putInt("modifier", 7);
        opinions.add(firstOpinion);
        CompoundTag duplicateOpinion = firstOpinion.copy();
        duplicateOpinion.putInt("modifier", 99);
        opinions.add(duplicateOpinion);
        village.put("opinion_modifiers", opinions);

        VillageRecord restored = VillageRecord.CODEC.parse(NbtOps.INSTANCE, village)
                .result().orElseThrow();
        assertEquals(1, restored.getMembers().size());
        assertEquals(7, restored.getOpinionModifier(playerId));
    }

    @Test
    void invalidVillagerPoiNumbersAreRejectedAtTheCodecBoundary() {
        CompoundTag invalidHealth = encodedMember();
        invalidHealth.putFloat("health", Float.NaN);
        assertTrue(VillagerPOIRecord.CODEC.parse(NbtOps.INSTANCE, invalidHealth).error().isPresent());

        CompoundTag invalidDepartureCounter = encodedMember();
        invalidDepartureCounter.putInt("departure_counter", -1);
        assertTrue(VillagerPOIRecord.CODEC.parse(NbtOps.INSTANCE, invalidDepartureCounter)
                .error().isPresent());

        CompoundTag invalidVerificationTime = encodedMember();
        invalidVerificationTime.putLong("last_verified_tick", -1L);
        assertTrue(VillagerPOIRecord.CODEC.parse(NbtOps.INSTANCE, invalidVerificationTime)
                .error().isPresent());
    }

    @Test
    void malformedMemberIsSkippedWithoutDiscardingItsVillage() {
        UUID villageId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        VillageRecord village = new VillageRecord(
                villageId, new BlockPos(0, 64, 0), new AABB(-4, 60, -4, 4, 68, 4));
        village.addMember(new VillagerPOIRecord(
                memberId, "Valid Member", "farmer", null, null, null, 20.0F,
                VillagerPOIRecord.Status.ACTIVE, 0, 10L));
        CompoundTag encoded = (CompoundTag) VillageRecord.CODEC.encodeStart(NbtOps.INSTANCE, village)
                .result().orElseThrow();
        encoded.getList("members", Tag.TAG_COMPOUND).add(new CompoundTag());

        VillageRecord restored = VillageRecord.CODEC.parse(NbtOps.INSTANCE, encoded)
                .result().orElseThrow();
        assertEquals(villageId, restored.getVillageId());
        assertEquals(1, restored.getMembers().size());
        assertTrue(restored.hasMember(memberId));
    }

    @Test
    void villageCollectionsRejectOversizedMembersAndRepairQueues() {
        CompoundTag oversizedMembers = encodedMinimalVillage();
        ListTag members = new ListTag();
        CompoundTag member = encodedMember();
        for (int i = 0; i <= VillageRecord.MAX_PERSISTED_MEMBERS; i++) {
            members.add(member.copy());
        }
        oversizedMembers.put("members", members);
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, oversizedMembers).error().isPresent());

        CompoundTag oversizedRepairQueue = encodedMinimalVillage();
        Tag position = BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, BlockPos.ZERO).result().orElseThrow();
        ListTag repairQueue = new ListTag();
        for (int i = 0; i <= VillageRecord.MAX_PERSISTED_FARMLAND_POSITIONS; i++) {
            repairQueue.add(position.copy());
        }
        oversizedRepairQueue.put("repair_queue", repairQueue);
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, oversizedRepairQueue).error().isPresent());

        CompoundTag oversizedDoors = encodedMinimalVillage();
        ListTag doors = new ListTag();
        for (int i = 0; i <= VillageRecord.MAX_PERSISTED_DOOR_POSITIONS; i++) {
            doors.add(position.copy());
        }
        oversizedDoors.put("door_registry", doors);
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, oversizedDoors).error().isPresent());

        CompoundTag oversizedDoorPlacements = encodedVillageWithDoorPlacement();
        CompoundTag placement = oversizedDoorPlacements.getList("door_placements", Tag.TAG_COMPOUND)
                .getCompound(0);
        ListTag placements = new ListTag();
        for (int i = 0; i <= VillageRecord.MAX_PERSISTED_DOOR_POSITIONS; i++) {
            placements.add(placement.copy());
        }
        oversizedDoorPlacements.put("door_placements", placements);
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, oversizedDoorPlacements).error().isPresent());
    }

    @Test
    void corruptDoorPlacementsAreRejected() {
        CompoundTag verticalFacing = encodedVillageWithDoorPlacement();
        verticalFacing.getList("door_placements", Tag.TAG_COMPOUND)
                .getCompound(0).putString("facing", "up");
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, verticalFacing).error().isPresent());

        CompoundTag unknownPosition = encodedVillageWithDoorPlacement();
        unknownPosition.put("door_registry", new ListTag());
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, unknownPosition).error().isPresent());

        CompoundTag missingPlacement = encodedVillageWithDoorPlacement();
        missingPlacement.put("door_placements", new ListTag());
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, missingPlacement).error().isPresent());

        CompoundTag duplicatePlacement = encodedVillageWithDoorPlacement();
        ListTag placements = duplicatePlacement.getList("door_placements", Tag.TAG_COMPOUND);
        placements.add(placements.getCompound(0).copy());
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, duplicatePlacement).error().isPresent());
    }

    @Test
    void persistedVillageAndMemberStringsAreBounded() {
        CompoundTag villageName = encodedMinimalVillage();
        villageName.putString("name", "v".repeat(65));
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, villageName).error().isPresent());

        CompoundTag welcomeMessage = encodedMinimalVillage();
        welcomeMessage.putString("welcome_message", "w".repeat(513));
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, welcomeMessage).error().isPresent());

        CompoundTag namingBiome = encodedMinimalVillage();
        namingBiome.putString("villager_naming_biome", "b".repeat(65));
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, namingBiome).error().isPresent());

        CompoundTag namingPair = encodedMinimalVillage();
        ListTag namingPairs = new ListTag();
        namingPairs.add(StringTag.valueOf("n".repeat(130)));
        namingPair.put("villager_naming_allocated_pairs", namingPairs);
        assertTrue(VillageRecord.CODEC.parse(NbtOps.INSTANCE, namingPair).error().isPresent());

        CompoundTag memberName = encodedMember();
        memberName.putString("display_name", "m".repeat(65));
        assertTrue(VillagerPOIRecord.CODEC.parse(NbtOps.INSTANCE, memberName).error().isPresent());

        CompoundTag profession = encodedMember();
        profession.putString("profession", "p".repeat(65));
        assertTrue(VillagerPOIRecord.CODEC.parse(NbtOps.INSTANCE, profession).error().isPresent());

        CompoundTag status = encodedMember();
        status.putString("status", "s".repeat(9));
        assertTrue(VillagerPOIRecord.CODEC.parse(NbtOps.INSTANCE, status).error().isPresent());
    }

    @Test
    void persistedRegistryCollectionsRejectOversizedInputBeforeEntryParsing() {
        CompoundTag oversizedChunks = new CompoundTag();
        ListTag chunks = new ListTag();
        for (int i = 0; i <= VillageRegistryData.MAX_PERSISTED_REGISTRY_ENTRIES; i++) {
            chunks.add(LongTag.valueOf(i));
        }
        oversizedChunks.put("processed_start_chunks", chunks);
        assertTrue(VillageRegistryData.CODEC.parse(NbtOps.INSTANCE, oversizedChunks).error().isPresent());

        CompoundTag oversizedVillages = new CompoundTag();
        ListTag villages = new ListTag();
        for (int i = 0; i <= VillageRegistryData.MAX_PERSISTED_VILLAGES; i++) {
            villages.add(new CompoundTag());
        }
        oversizedVillages.put("villages", villages);
        assertTrue(VillageRegistryData.CODEC.parse(NbtOps.INSTANCE, oversizedVillages).error().isPresent());
    }

    @Test
    void durableMutatorsDirtyAndViewsAreReadOnly() {
        VillageRegistryData data = new VillageRegistryData();
        UUID villageId = UUID.randomUUID();
        BlockPos bell = new BlockPos(0, 64, 0);
        AABB bounds = new AABB(-4, 60, -4, 4, 68, 4);

        VillageRecord village = data.getOrCreateVillage(villageId, bell, bounds);
        assertTrue(data.isDirty());
        data.setDirty(false);
        assertEquals(village, data.getOrCreateVillage(villageId, bell, bounds));
        assertFalse(data.isDirty());

        VillagerPOIRecord member = new VillagerPOIRecord(
                UUID.randomUUID(), "Member", "farmer", null, null, null, 20.0F,
                VillagerPOIRecord.Status.ACTIVE, 0, 10L);
        data.registerVillager(villageId, member);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.removeVillager(villageId, member.getVillagerUUID());
        assertTrue(data.isDirty());

        data.setDirty(false);
        ChunkPos chunk = new ChunkPos(-1, 2);
        data.markVillageRegistered(chunk);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.markVillageRegistered(chunk);
        assertTrue(data.isDirty(), "existing markVillageRegistered behavior always dirties");

        UUID bankVillage = UUID.randomUUID();
        data.setDirty(false);
        data.markBankGenerated(bankVillage);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.markBankGenerated(bankVillage);
        assertFalse(data.isDirty());

        UUID libraryVillage = UUID.randomUUID();
        data.setDirty(false);
        data.markLibraryGenerated(libraryVillage);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.markLibraryGenerated(libraryVillage);
        assertFalse(data.isDirty());

        UUID lumbermillVillage = UUID.randomUUID();
        data.setDirty(false);
        data.markLumbermillGenerated(lumbermillVillage);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.markLumbermillGenerated(lumbermillVillage);
        assertFalse(data.isDirty());

        data.setDirty(false);
        data.markLumbermillStructureGenerated(987654321L);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.markLumbermillStructureGenerated(987654321L);
        assertFalse(data.isDirty());

        data.setDirty(false);
        BlockPos abandonedVault = new BlockPos(40, 64, -12);
        data.markAbandonedVaultPosition(abandonedVault);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.markAbandonedVaultPosition(abandonedVault);
        assertFalse(data.isDirty());

        BlockPos bankPos = new BlockPos(8, 64, 8);
        data.registerBankPosition(bankVillage, bankPos);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.registerBankPosition(bankVillage, bankPos);
        assertFalse(data.isDirty());
        data.deregisterBankPosition(bankVillage, bankPos);
        assertTrue(data.isDirty());

        data.setDirty(false);
        BoundingBox box = new BoundingBox(-2, 60, -2, 2, 64, 2);
        data.addPendingManagerPlacement(villageId, box);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.removePendingManagerPlacement(data.getPendingManagerPlacements().get(0));
        assertTrue(data.isDirty());

        data.setDirty(false);
        data.assignLegacyVillageNumberName(village);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.assignLegacyVillageNumberName(village);
        assertFalse(data.isDirty());

        assertThrows(UnsupportedOperationException.class,
                () -> data.getVillages().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> village.getMembers().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> data.getSnapshot(villageId).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> data.getPendingManagerPlacements().clear());

        data.setDirty(false);
        data.clearAll();
        assertTrue(data.isDirty());
    }

    private static VillageRegistryData populatedRegistry() {
        VillageRegistryData data = new VillageRegistryData();
        UUID firstId = UUID.nameUUIDFromBytes("l4-first".getBytes());
        UUID secondId = UUID.nameUUIDFromBytes("l4-second".getBytes());
        BlockPos firstBell = new BlockPos(-10, 70, 12);
        VillageRecord first = data.getOrCreateVillage(
                firstId, firstBell, new AABB(-20.5, 60.0, 2.5, 4.5, 90.0, 24.5));
        data.assignLegacyVillageNumberName(first);
        first.setName("First Village");
        first.setWelcomeMessage("Codec welcome");
        first.setAbandonedVillage(true);
        first.setInitialScanAnchorBounds(new AABB(-18, 62, 4, 3, 88, 22));
        VillagerPOIRecord member = new VillagerPOIRecord(
                UUID.nameUUIDFromBytes("member".getBytes()),
                "Codec Villager", "farmer", new BlockPos(-8, 70, 12),
                new BlockPos(-7, 70, 12), UUID.nameUUIDFromBytes("family".getBytes()),
                19.5F, VillagerPOIRecord.Status.ACTIVE, 2, 123L);
        data.registerVillager(firstId, member);
        first.addFarmland(new BlockPos(-2, 70, 3));
        first.addDoor(new BlockPos(-4, 70, 3), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT));
        first.addToRepairQueue(new BlockPos(-2, 70, 3));
        first.claimPosition(new BlockPos(-2, 70, 3));
        first.adjustOpinionModifier(UUID.nameUUIDFromBytes("player".getBytes()), 7);
        data.registerVillageManager(firstId, new BlockPos(1, 70, 1));

        VillageRecord second = data.getOrCreateVillage(
                secondId, new BlockPos(30, 70, 30), new AABB(25, 65, 25, 35, 75, 35));
        data.assignLegacyVillageNumberName(second);
        second.setName("Second Village");

        data.markVillageRegistered(new ChunkPos(-3, 4));
        data.markBankGenerated(secondId);
        data.markLibraryGenerated(secondId);
        data.markLumbermillGenerated(secondId);
        data.markLumbermillStructureGenerated(123456789L);
        data.markAbandonedVaultPosition(new BlockPos(40, 64, -12));
        data.registerBankPosition(secondId, new BlockPos(100, 65, -100));
        data.addPendingManagerPlacement(firstId, new BoundingBox(-8, 10, -6, 12, 22, 14));
        return data;
    }

    private static CompoundTag encodedMinimalVillage() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-1, -1, -1, 1, 1, 1));
        return (CompoundTag) VillageRecord.CODEC.encodeStart(NbtOps.INSTANCE, village)
                .result().orElseThrow();
    }

    private static CompoundTag encodedVillageWithDoorPlacement() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-1, -1, -1, 1, 1, 1));
        village.addDoor(BlockPos.ZERO, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT));
        return (CompoundTag) VillageRecord.CODEC.encodeStart(NbtOps.INSTANCE, village)
                .result().orElseThrow();
    }

    private static CompoundTag encodedMember() {
        VillagerPOIRecord member = new VillagerPOIRecord(
                UUID.randomUUID(), "Member", "farmer", null, null, null, 20.0F,
                VillagerPOIRecord.Status.ACTIVE, 0, 0L);
        return (CompoundTag) VillagerPOIRecord.CODEC.encodeStart(NbtOps.INSTANCE, member)
                .result().orElseThrow();
    }
}
