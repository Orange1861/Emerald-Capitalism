package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageFarmJournalTest {

    private static final BlockPos CENTER = new BlockPos(100, 64, -200);

    @BeforeAll
    static void detectVersion() {
        // The data version written into a flushed file comes from the detected game version.
        SharedConstants.tryDetectVersion();
    }

    private static FarmPlacement farm(int x, int z) {
        return new FarmPlacement(new BlockPos(x, 64, z), ResourceLocation.parse("minecraft:farm"),
                ResourceLocation.parse("minecraft:farm_plains"), Rotation.CLOCKWISE_90, 9, 7);
    }

    @Test
    void aVillageWithNoJournalIsResolved() {
        VillageFarmSavedData data = new VillageFarmSavedData();
        assertTrue(data.farmsResolved(CENTER));
        assertFalse(data.hasFarmJournal(CENTER));
        assertTrue(data.farmsToPlace(CENTER).isEmpty());
    }

    @Test
    void plannedFarmsStayPendingUntilEachIsDone() {
        VillageFarmSavedData data = new VillageFarmSavedData();
        FarmPlacement first = farm(0, 0);
        FarmPlacement second = farm(20, 0);
        data.recordPlannedFarms(CENTER, List.of(first, second));

        assertFalse(data.farmsResolved(CENTER));
        assertEquals(List.of(first, second), data.farmsToPlace(CENTER));

        data.beginFarmPlacement(CENTER, first);
        data.completeFarmPlacement(CENTER, first);
        assertEquals(List.of(second), data.farmsToPlace(CENTER));
        assertFalse(data.farmsResolved(CENTER), "one farm is still unfinished");

        data.beginFarmPlacement(CENTER, second);
        data.completeFarmPlacement(CENTER, second);
        assertTrue(data.farmsResolved(CENTER));
        assertEquals(0, data.unfinishedFarmCount(CENTER));
    }

    @Test
    void aFarmThatKeepsFailingIsGivenUpAfterTheAttemptLimit() {
        VillageFarmSavedData data = new VillageFarmSavedData();
        FarmPlacement stuck = farm(0, 0);
        data.recordPlannedFarms(CENTER, List.of(stuck));

        for (int attempt = 1; attempt < VillageFarmSavedData.MAX_PLACEMENT_ATTEMPTS; attempt++) {
            data.beginFarmPlacement(CENTER, stuck);
            assertFalse(data.farmsResolved(CENTER), "attempt " + attempt + " leaves the farm pending");
            assertEquals(List.of(stuck), data.farmsToPlace(CENTER));
        }
        data.beginFarmPlacement(CENTER, stuck);
        assertTrue(data.farmsResolved(CENTER), "out of attempts, the village is resolved");
        assertTrue(data.farmsToPlace(CENTER).isEmpty());
        assertEquals(1, data.unfinishedFarmCount(CENTER), "the given-up farm is still counted as unfinished");
    }

    @Test
    void theOriginalPlannedSetIsKeptWhenPlanningRunsAgain() {
        VillageFarmSavedData data = new VillageFarmSavedData();
        FarmPlacement original = farm(0, 0);
        data.recordPlannedFarms(CENTER, List.of(original));
        data.recordPlannedFarms(CENTER, List.of(farm(50, 50)));
        assertEquals(List.of(original), data.farmsToPlace(CENTER));
    }

    @Test
    void markingFarmsPlacedClearsTheJournalAndBlocksANewOne() {
        VillageFarmSavedData data = new VillageFarmSavedData();
        data.recordPlannedFarms(CENTER, List.of(farm(0, 0)));
        data.markFarmsPlaced(CENTER);
        assertFalse(data.hasFarmJournal(CENTER));
        data.recordPlannedFarms(CENTER, List.of(farm(0, 0)));
        assertFalse(data.hasFarmJournal(CENTER), "a village with its farms placed takes no journal");
    }

    @Test
    void theJournalSurvivesSaveAndReload() {
        VillageFarmSavedData original = new VillageFarmSavedData();
        FarmPlacement first = farm(0, 0);
        FarmPlacement second = farm(20, 0);
        original.recordPlannedFarms(CENTER, List.of(first, second));
        original.beginFarmPlacement(CENTER, first);
        original.beginFarmPlacement(CENTER, second);
        original.completeFarmPlacement(CENTER, second);

        CompoundTag saved = original.save(new CompoundTag(), null);
        VillageFarmSavedData restored = VillageFarmSavedData.load(saved, null);

        assertTrue(restored.hasFarmJournal(CENTER));
        assertEquals(List.of(first), restored.farmsToPlace(CENTER), "the interrupted farm is replayed");
        assertFalse(restored.farmsResolved(CENTER));
    }

    @Test
    void aSavedJournalForAVillageWithItsFarmsPlacedIsDropped() {
        VillageFarmSavedData original = new VillageFarmSavedData();
        original.recordPlannedFarms(CENTER, List.of(farm(0, 0)));
        CompoundTag saved = original.save(new CompoundTag(), null);
        ListTag placed = new ListTag();
        placed.add(BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, CENTER).result().orElseThrow());
        saved.put("farms_placed_villages", placed);

        VillageFarmSavedData restored = VillageFarmSavedData.load(saved, null);
        assertTrue(restored.areFarmsPlaced(CENTER));
        assertFalse(restored.hasFarmJournal(CENTER));
    }

    @Test
    void malformedAndOversizedJournalDataIsRejectedSafely() {
        Tag placement = FarmPlacement.CODEC.encodeStart(NbtOps.INSTANCE, farm(0, 0)).result().orElseThrow();
        CompoundTag badEntry = new CompoundTag();
        badEntry.put("placement", placement.copy());
        badEntry.putInt("attempts", -5);
        assertTrue(FarmJournalEntry.CODEC.parse(NbtOps.INSTANCE, badEntry).error().isPresent(),
                "a negative attempt count must not decode");

        ListTag farms = new ListTag();
        for (int index = 0; index <= VillageFarmSavedData.MAX_JOURNAL_FARMS_PER_VILLAGE; index++) {
            CompoundTag entry = new CompoundTag();
            entry.put("placement", placement.copy());
            farms.add(entry);
        }
        CompoundTag village = new CompoundTag();
        village.put("center", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, CENTER).result().orElseThrow());
        village.put("farms", farms);
        ListTag journal = new ListTag();
        journal.add(village);
        CompoundTag root = new CompoundTag();
        root.put("farm_journal", journal);

        assertTrue(VillageFarmSavedData.CODEC.parse(NbtOps.INSTANCE, root).error().isPresent(),
                "more farms than the per-village limit must not decode");
        // Like the other saved sets, an oversized list recovers only a bounded prefix.
        assertTrue(VillageFarmSavedData.load(root, null).farmsToPlace(CENTER).size()
                <= VillageFarmSavedData.MAX_JOURNAL_FARMS_PER_VILLAGE,
                "recovery from an oversized journal must stay bounded");

        CompoundTag footprintTag = (CompoundTag) placement.copy();
        footprintTag.putInt("footprint_x", 100_000);
        CompoundTag oversizedFootprint = new CompoundTag();
        oversizedFootprint.put("placement", footprintTag);
        assertTrue(FarmJournalEntry.CODEC.parse(NbtOps.INSTANCE, oversizedFootprint).error().isPresent(),
                "an absurd footprint must not decode");
    }

    @Test
    void aFlushedJournalIsAFileTheLoaderReadsAndLeavesNoTempFile(@TempDir Path folder) throws Exception {
        VillageFarmSavedData data = new VillageFarmSavedData();
        FarmPlacement first = farm(0, 0);
        data.recordPlannedFarms(CENTER, List.of(first));
        data.beginFarmPlacement(CENTER, first);
        Path file = folder.resolve("data").resolve("emeraldcapitalism_village_farms.dat");

        data.flushTo(file, null);

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".journal-tmp")),
                "the temporary file must be moved into place");
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        assertTrue(root.contains("DataVersion"), "the file must carry the data version the loader expects");
        VillageFarmSavedData loaded = VillageFarmSavedData.load(root.getCompound("data"), null);
        assertEquals(List.of(first), loaded.farmsToPlace(CENTER));
        assertTrue(data.isDirty(), "the normal save must still rewrite the data");
    }

    @Test
    void aSecondFlushReplacesTheFileWithTheNewerJournal(@TempDir Path folder) throws Exception {
        VillageFarmSavedData data = new VillageFarmSavedData();
        FarmPlacement first = farm(0, 0);
        data.recordPlannedFarms(CENTER, List.of(first));
        Path file = folder.resolve("farms.dat");
        data.flushTo(file, null);
        data.beginFarmPlacement(CENTER, first);
        data.completeFarmPlacement(CENTER, first);
        data.flushTo(file, null);

        VillageFarmSavedData loaded = VillageFarmSavedData.load(
                NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"), null);
        assertTrue(loaded.farmsResolved(CENTER));
    }
}
