package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** Codec for the two durable village-position sets. */
    public static final Codec<VillageFarmSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_VILLAGE_POSITIONS)
                    .optionalFieldOf("detected_villages", List.of())
                    .forGetter(data -> new ArrayList<>(data.detectedVillages)),
            BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_VILLAGE_POSITIONS)
                    .optionalFieldOf("farms_placed_villages", List.of())
                    .forGetter(data -> new ArrayList<>(data.farmsPlacedVillages))
    ).apply(instance, VillageFarmSavedData::fromCodec));

    private final Set<BlockPos> detectedVillages = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> farmsPlacedVillages = ConcurrentHashMap.newKeySet();

    public VillageFarmSavedData() {
    }

    private static VillageFarmSavedData fromCodec(
            List<BlockPos> detectedVillages,
            List<BlockPos> farmsPlacedVillages
    ) {
        VillageFarmSavedData data = new VillageFarmSavedData();
        data.detectedVillages.addAll(detectedVillages);
        data.farmsPlacedVillages.addAll(farmsPlacedVillages);
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
        if (farmsPlacedVillages.add(center)) {
            setDirty();
            return true;
        }
        return false;
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
