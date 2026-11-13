package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists the set of village center positions that have already been detected.
 * This prevents duplicate detection on chunk reload or server restart.
 *
 * <p>Thread safety: uses a ConcurrentHashMap-backed set since chunk loading
 * can occur on multiple threads during world generation.</p>
 */
public class VillageFarmSavedData extends SavedData {

    private static final String DATA_NAME = "emeraldcapitalism_village_farms";
    static final int MAX_PERSISTED_VILLAGE_POSITIONS = 65_536;
    /** A farm whose placement started this many times without finishing is given up on. */
    public static final int MAX_PLACEMENT_ATTEMPTS = 3;
    static final int MAX_JOURNAL_FARMS_PER_VILLAGE = 64;

    /** One village's farm journal as persisted. */
    record JournalVillage(BlockPos center, List<FarmJournalEntry> farms) {
        static final Codec<JournalVillage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("center").forGetter(JournalVillage::center),
                FarmJournalEntry.CODEC.sizeLimitedListOf(MAX_JOURNAL_FARMS_PER_VILLAGE)
                        .fieldOf("farms").forGetter(JournalVillage::farms)
        ).apply(instance, JournalVillage::new));
    }

    /** Codec for the two durable village-position sets. */
    public static final Codec<VillageFarmSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_VILLAGE_POSITIONS)
                    .optionalFieldOf("detected_villages", List.of())
                    .forGetter(data -> new ArrayList<>(data.detectedVillages)),
            BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_VILLAGE_POSITIONS)
                    .optionalFieldOf("farms_placed_villages", List.of())
                    .forGetter(data -> new ArrayList<>(data.farmsPlacedVillages)),
            JournalVillage.CODEC.sizeLimitedListOf(MAX_PERSISTED_VILLAGE_POSITIONS)
                    .optionalFieldOf("farm_journal", List.of())
                    .forGetter(data -> data.farmJournal.entrySet().stream()
                            .map(entry -> new JournalVillage(entry.getKey(), entry.getValue())).toList())
    ).apply(instance, VillageFarmSavedData::fromCodec));

    private final Set<BlockPos> detectedVillages = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> farmsPlacedVillages = ConcurrentHashMap.newKeySet();
    /** Planned farms of villages whose farm set is not yet resolved; values are replaced, never mutated. */
    private final Map<BlockPos, List<FarmJournalEntry>> farmJournal = new ConcurrentHashMap<>();

    public VillageFarmSavedData() {
    }

    private static VillageFarmSavedData fromCodec(
            List<BlockPos> detectedVillages,
            List<BlockPos> farmsPlacedVillages,
            List<JournalVillage> journal
    ) {
        VillageFarmSavedData data = new VillageFarmSavedData();
        data.detectedVillages.addAll(detectedVillages);
        data.farmsPlacedVillages.addAll(farmsPlacedVillages);
        for (JournalVillage village : journal) {
            // A village whose farms are already marked placed has no use for a journal.
            if (!village.farms().isEmpty() && !data.farmsPlacedVillages.contains(village.center())) {
                data.farmJournal.put(village.center(), List.copyOf(village.farms()));
            }
        }
        return data;
    }

    /**
     * Loads previously detected village positions from NBT.
     */
    public static VillageFarmSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        return decodeFromTag(tag);
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        return encodeToTag(this, tag);
    }

    private static CompoundTag encodeToTag(VillageFarmSavedData data, CompoundTag target) {
        DataResult<net.minecraft.nbt.Tag> encoded = CODEC.encodeStart(NbtOps.INSTANCE, data);
        return encoded.resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not encode village farm data: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .map(encodedTag -> {
                    target.merge(encodedTag);
                    return target;
                })
                .orElse(target);
    }

    private static VillageFarmSavedData decodeFromTag(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not decode village farm data: {}", message))
                .orElseGet(VillageFarmSavedData::new);
    }

    /**
     * Returns true if this village center has already been detected.
     */
    public boolean isVillageDetected(BlockPos center) {
        return detectedVillages.contains(center);
    }

    /**
     * Marks a village center as detected. Returns true if this is a new detection
     * (the position was not previously in the set).
     */
    public boolean markVillageDetected(BlockPos center) {
        if (detectedVillages.add(center)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Returns an unmodifiable view of all detected village positions.
     */
    public Set<BlockPos> getDetectedVillages() {
        return Collections.unmodifiableSet(detectedVillages);
    }

    /**
     * Returns true if farms have already been placed for this village.
     */
    public boolean areFarmsPlaced(BlockPos center) {
        return farmsPlacedVillages.contains(center);
    }

    /** Returns an unmodifiable view of all village positions with placed farms. */
    public Set<BlockPos> getFarmsPlacedVillages() {
        return Collections.unmodifiableSet(farmsPlacedVillages);
    }

    /**
     * Marks a village as having had outskirt farms placed.
     * Returns true if this is a new marking.
     */
    public boolean markFarmsPlaced(BlockPos center) {
        boolean journalCleared = farmJournal.remove(center) != null;
        if (farmsPlacedVillages.add(center)) {
            setDirty();
            return true;
        }
        if (journalCleared) {
            setDirty();
        }
        return false;
    }

    /**
     * Writes this data to the overworld's data file now. A farm's journal entry must be on disk
     * before the farm's first block is written: chunks can be saved on unload, ahead of the next
     * saved-data write, and a crash in between would leave part-built terrain and no journal.
     * Only the journal-creating call needs this. A completed farm is deliberately not flushed,
     * because a durable "done" ahead of the chunk save would lose the farm instead.
     */
    public void flushNow(ServerLevel level) {
        Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        flushTo(file, level.registryAccess());
    }

    /**
     * Writes {@code file} in the format the data storage reads: a compressed {@code data} tag with
     * the data version. The file is written beside it and moved into place, so a crash during the
     * write leaves the previous file intact. This data stays dirty for the normal save.
     */
    public void flushTo(Path file, HolderLookup.Provider registries) {
        Path temp = file.resolveSibling(file.getFileName() + ".journal-tmp");
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.put("data", save(new CompoundTag(), registries));
            NbtUtils.addCurrentDataVersion(root);
            NbtIo.writeCompressed(root, temp);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            EmeraldCapitalism.LOGGER.warn("[ECAP] Could not flush the village farm journal to {}: {}",
                    file, exception.toString());
        }
    }

    /** Whether a farm set was planned for this village and is not yet resolved. */
    public boolean hasFarmJournal(BlockPos center) {
        return farmJournal.containsKey(center);
    }

    /** The planned farms that still need placing: not done, with attempts remaining. */
    public List<FarmPlacement> farmsToPlace(BlockPos center) {
        return farmJournal.getOrDefault(center, List.of()).stream()
                .filter(FarmJournalEntry::pending)
                .map(FarmJournalEntry::placement)
                .toList();
    }

    /**
     * Saves the planned farm set before any farm is placed. Ignored when the village already
     * has a journal, so a retry replays the original sites instead of selecting new ones.
     */
    public void recordPlannedFarms(BlockPos center, List<FarmPlacement> placements) {
        if (placements.isEmpty() || farmsPlacedVillages.contains(center) || farmJournal.containsKey(center)) {
            return;
        }
        List<FarmJournalEntry> entries = placements.stream()
                .limit(MAX_JOURNAL_FARMS_PER_VILLAGE)
                .map(placement -> new FarmJournalEntry(placement, 0, false))
                .toList();
        if (farmJournal.putIfAbsent(center, entries) == null) {
            setDirty();
        }
    }

    /** Counts one more attempt for {@code placement}; call before its first block is written. */
    public void beginFarmPlacement(BlockPos center, FarmPlacement placement) {
        updateEntry(center, placement, FarmJournalEntry::begun);
    }

    /** Records that {@code placement} completed. */
    public void completeFarmPlacement(BlockPos center, FarmPlacement placement) {
        updateEntry(center, placement, FarmJournalEntry::completed);
    }

    /**
     * Whether the village's farm set needs no further placement: it has no journal, or every farm
     * is either done or out of attempts. Only then may the village be marked as having its farms.
     */
    public boolean farmsResolved(BlockPos center) {
        List<FarmJournalEntry> entries = farmJournal.get(center);
        return entries == null || entries.stream().noneMatch(FarmJournalEntry::pending);
    }

    /** How many journaled farms of the village are not done. */
    public int unfinishedFarmCount(BlockPos center) {
        return (int) farmJournal.getOrDefault(center, List.of()).stream()
                .filter(entry -> !entry.done()).count();
    }

    private void updateEntry(BlockPos center, FarmPlacement placement,
                             java.util.function.UnaryOperator<FarmJournalEntry> change) {
        farmJournal.computeIfPresent(center, (key, entries) -> {
            List<FarmJournalEntry> updated = new ArrayList<>(entries.size());
            boolean changed = false;
            for (FarmJournalEntry entry : entries) {
                if (entry.placement().equals(placement)) {
                    updated.add(change.apply(entry));
                    changed = true;
                } else {
                    updated.add(entry);
                }
            }
            return changed ? List.copyOf(updated) : entries;
        });
        setDirty();
    }

    /**
     * Gets or creates the VillageFarmSavedData for the given server level.
     * Should be called on the overworld level.
     */
    public static VillageFarmSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(VillageFarmSavedData::new, VillageFarmSavedData::load, null),
                DATA_NAME
        );
    }
}
