package com.orangevillager61.emeraldcapitalism.world.village;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIDataCache;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameRefreshScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * World-level SavedData that persists all tracked villages and their villager POI records.
 */
public class VillageRegistryData extends SavedData {

    private static final String DATA_NAME = "emeraldcapitalism_village_registry";
    private static final int MAX_PERSISTED_WORLD_COORDINATE = 30_000_000;
    private static final int MAX_PERSISTED_VILLAGE_NUMBER = 2_000_000_000;
    static final int MAX_PERSISTED_VILLAGES = 65_536;
    static final int MAX_PERSISTED_REGISTRY_ENTRIES = 65_536;

    private record BankPosition(UUID villageId, BlockPos bankPosition) {
        private static final Codec<BankPosition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("village_id").forGetter(BankPosition::villageId),
                BlockPos.CODEC.fieldOf("bank_position").forGetter(BankPosition::bankPosition)
        ).apply(instance, BankPosition::new));
    }

    private record PendingPlacementState(
            UUID villageId,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        private static final Codec<PendingPlacementState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("village_id").forGetter(PendingPlacementState::villageId),
                Codec.intRange(-MAX_PERSISTED_WORLD_COORDINATE, MAX_PERSISTED_WORLD_COORDINATE)
                        .fieldOf("min_x").forGetter(PendingPlacementState::minX),
                Codec.intRange(-MAX_PERSISTED_WORLD_COORDINATE, MAX_PERSISTED_WORLD_COORDINATE)
                        .fieldOf("min_y").forGetter(PendingPlacementState::minY),
                Codec.intRange(-MAX_PERSISTED_WORLD_COORDINATE, MAX_PERSISTED_WORLD_COORDINATE)
                        .fieldOf("min_z").forGetter(PendingPlacementState::minZ),
                Codec.intRange(-MAX_PERSISTED_WORLD_COORDINATE, MAX_PERSISTED_WORLD_COORDINATE)
                        .fieldOf("max_x").forGetter(PendingPlacementState::maxX),
                Codec.intRange(-MAX_PERSISTED_WORLD_COORDINATE, MAX_PERSISTED_WORLD_COORDINATE)
                        .fieldOf("max_y").forGetter(PendingPlacementState::maxY),
                Codec.intRange(-MAX_PERSISTED_WORLD_COORDINATE, MAX_PERSISTED_WORLD_COORDINATE)
                        .fieldOf("max_z").forGetter(PendingPlacementState::maxZ)
        ).apply(instance, PendingPlacementState::new));

        private static PendingPlacementState from(PendingManagerPlacement placement) {
            BoundingBox box = placement.structureBox();
            return new PendingPlacementState(
                    placement.villageId(),
                    box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ()
            );
        }

        private PendingManagerPlacement toPendingPlacement() {
            return new PendingManagerPlacement(
                    villageId,
                    new BoundingBox(
                            Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ),
                            Math.max(minX, maxX), Math.max(minY, maxY), Math.max(minZ, maxZ)
                    )
            );
        }
    }

    /**
     * A list codec that keeps valid villages when one nested village is malformed.
     * The skipped-entry policy is part of the new format's corruption handling.
     */
    private static final Codec<List<VillageRecord>> VILLAGES_CODEC = Codec.of(
            new Encoder<>() {
                @Override
                public <T> DataResult<T> encode(List<VillageRecord> input, DynamicOps<T> ops, T prefix) {
                    return VillageRecord.CODEC.sizeLimitedListOf(MAX_PERSISTED_VILLAGES)
                            .encode(input, ops, prefix);
                }
            },
            new Decoder<>() {
                @Override
                public <T> DataResult<Pair<List<VillageRecord>, T>> decode(DynamicOps<T> ops, T input) {
                    return ops.getStream(input).flatMap(elements -> {
                        List<T> encodedVillages = elements.limit((long) MAX_PERSISTED_VILLAGES + 1).toList();
                        if (encodedVillages.size() > MAX_PERSISTED_VILLAGES) {
                            return DataResult.error(() -> "Village registry exceeds "
                                    + MAX_PERSISTED_VILLAGES + " persisted villages");
                        }
                        List<VillageRecord> villages = new ArrayList<>();
                        for (T element : encodedVillages) {
                            VillageRecord.CODEC.parse(ops, element)
                                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                                        "[ECAP] Skipping corrupt nested village record: {}", message))
                                .ifPresent(villages::add);
                        }
                        return DataResult.success(Pair.of(villages, ops.empty()));
                    });
                }
            }
    );

    /** Codec for all durable registry state; VM positions and active work are transient. */
    public static final Codec<VillageRegistryData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VILLAGES_CODEC.optionalFieldOf("villages", List.of())
                    .forGetter(data -> new ArrayList<>(data.villages.values())),
            Codec.LONG.sizeLimitedListOf(MAX_PERSISTED_REGISTRY_ENTRIES)
                    .optionalFieldOf("processed_start_chunks", List.of())
                    .forGetter(data -> new ArrayList<>(data.processedStartChunks)),
            UUIDUtil.CODEC.sizeLimitedListOf(MAX_PERSISTED_REGISTRY_ENTRIES)
                    .optionalFieldOf("generated_bank_villages", List.of())
                    .forGetter(data -> new ArrayList<>(data.generatedBankVillages)),
            UUIDUtil.CODEC.sizeLimitedListOf(MAX_PERSISTED_REGISTRY_ENTRIES)
                    .optionalFieldOf("generated_library_villages", List.of())
                    .forGetter(data -> new ArrayList<>(data.generatedLibraryVillages)),
            UUIDUtil.CODEC.sizeLimitedListOf(MAX_PERSISTED_REGISTRY_ENTRIES)
                    .optionalFieldOf("generated_lumbermill_villages", List.of())
                    .forGetter(data -> new ArrayList<>(data.generatedLumbermillVillages)),
            Codec.LONG.sizeLimitedListOf(MAX_PERSISTED_REGISTRY_ENTRIES)
                    .optionalFieldOf("generated_lumbermill_structures", List.of())
                    .forGetter(data -> new ArrayList<>(data.generatedLumbermillStructures)),
            Codec.LONG.sizeLimitedListOf(MAX_PERSISTED_REGISTRY_ENTRIES)
                    .optionalFieldOf("abandoned_vault_positions", List.of())
                    .forGetter(data -> new ArrayList<>(data.abandonedVaultPositions)),
            BankPosition.CODEC.sizeLimitedListOf(MAX_PERSISTED_REGISTRY_ENTRIES)
                    .optionalFieldOf("bank_positions", List.of())
                    .forGetter(VillageRegistryData::bankPositionEntries),
            PendingPlacementState.CODEC.sizeLimitedListOf(MAX_PERSISTED_REGISTRY_ENTRIES)
                    .optionalFieldOf("pending_manager_placements", List.of())
                    .forGetter(data -> data.pendingManagerPlacements.stream()
                            .map(PendingPlacementState::from).toList()),
            Codec.INT.optionalFieldOf("next_village_number", 1)
                    .forGetter(data -> data.nextVillageNumber)
    ).apply(instance, VillageRegistryData::fromCodec));

    private final Map<UUID, VillageRecord> villages = new HashMap<>();
    private final Set<Long> processedStartChunks = new HashSet<>();
    /** Village IDs for which this mod has successfully generated its bank or abandoned-village replacement. */
    private final Set<UUID> generatedBankVillages = new HashSet<>();
    private final Set<UUID> generatedLibraryVillages = new HashSet<>();
    private final Set<UUID> generatedLumbermillVillages = new HashSet<>();
    private final Set<Long> generatedLumbermillStructures = new HashSet<>();
    /** Persistent positions for abandoned-village vaults placed outside vanilla structure starts. */
    private final Set<Long> abandonedVaultPositions = new HashSet<>();
    /** Persistent villageId → registered bank position, used to avoid volume scans. */
    private final Map<UUID, BlockPos> bankPositions = new HashMap<>();
    private final List<PendingManagerPlacement> pendingManagerPlacements = new ArrayList<>();
    private int nextVillageNumber = 1;

    /**
     * Transient (never persisted) map of villageId → VillageManagerBlockEntity position.
     * Populated when a {@link com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity}
     * loads or is placed. Used by other systems to find the active VM for a village.
     */
    private final Map<UUID, BlockPos> vmPositions = new HashMap<>();

    /**
     * Represents a village whose manager block could not be placed immediately
     * because the bell chunk wasn't fully loaded yet.
     */
    public record PendingManagerPlacement(UUID villageId, BoundingBox structureBox) {}

    public VillageRegistryData() {
    }

    private static VillageRegistryData fromCodec(
            List<VillageRecord> villages,
            List<Long> processedStartChunks,
            List<UUID> generatedBankVillages,
            List<UUID> generatedLibraryVillages,
            List<UUID> generatedLumbermillVillages,
            List<Long> generatedLumbermillStructures,
            List<Long> abandonedVaultPositions,
            List<BankPosition> bankPositions,
            List<PendingPlacementState> pendingManagerPlacements,
            int nextVillageNumber
    ) {
        VillageRegistryData data = new VillageRegistryData();
        for (VillageRecord village : villages) {
            if (data.villages.putIfAbsent(village.getVillageId(), village) != null) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Ignoring duplicate village record for {}", village.getVillageId());
            }
        }
        data.processedStartChunks.addAll(processedStartChunks);
        data.generatedBankVillages.addAll(generatedBankVillages);
        data.generatedLibraryVillages.addAll(generatedLibraryVillages);
        data.generatedLumbermillVillages.addAll(generatedLumbermillVillages);
        data.generatedLumbermillStructures.addAll(generatedLumbermillStructures);
        data.abandonedVaultPositions.addAll(abandonedVaultPositions);
        for (BankPosition bankPosition : bankPositions) {
            if (data.bankPositions.putIfAbsent(
                    bankPosition.villageId(), bankPosition.bankPosition().immutable()) != null) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Ignoring duplicate bank position for {}", bankPosition.villageId());
            }
        }
        for (PendingPlacementState pending : pendingManagerPlacements) {
            data.pendingManagerPlacements.add(pending.toPendingPlacement());
        }
        data.nextVillageNumber = normalizeNextVillageNumber(nextVillageNumber);
        return data;
    }

    private static int normalizeNextVillageNumber(int value) {
        return value >= 1 && value < MAX_PERSISTED_VILLAGE_NUMBER ? value : 1;
    }

    private List<BankPosition> bankPositionEntries() {
        List<BankPosition> entries = new ArrayList<>(bankPositions.size());
        for (Map.Entry<UUID, BlockPos> entry : bankPositions.entrySet()) {
            entries.add(new BankPosition(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    // SavedData overrides

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        return encodeToTag(this, tag);
    }

    public static VillageRegistryData load(CompoundTag tag, HolderLookup.Provider registries) {
        return decodeFromTag(tag);
    }

    private static CompoundTag encodeToTag(VillageRegistryData data, CompoundTag target) {
        DataResult<net.minecraft.nbt.Tag> encoded = CODEC.encodeStart(NbtOps.INSTANCE, data);
        return encoded.resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not encode village registry data: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .map(encodedTag -> {
                    target.merge(encodedTag);
                    return target;
                })
                .orElse(target);
    }

    private static VillageRegistryData decodeFromTag(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not decode village registry data: {}", message))
                .orElseGet(VillageRegistryData::new);
    }

    // Factory

    public static VillageRegistryData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(VillageRegistryData::new, VillageRegistryData::load, null),
                DATA_NAME
        );
    }

    // Chunk deduplication

    /**
     * Returns true if the village structure start at the given chunk has already been processed.
     */
    public boolean isVillageRegistered(ChunkPos chunkPos) {
        return processedStartChunks.contains(chunkPos.toLong());
    }

    /**
     * Marks the village structure start at the given chunk as processed.
     */
    public void markVillageRegistered(ChunkPos chunkPos) {
        processedStartChunks.add(chunkPos.toLong());
        setDirty();
    }

    /** Returns whether the generated bank/replacement structure has already been committed. */
    public boolean hasGeneratedBank(UUID villageId) {
        return generatedBankVillages.contains(villageId);
    }

    /** Records successful generated infrastructure, preventing duplicate placement after reloads. */
    public void markBankGenerated(UUID villageId) {
        if (generatedBankVillages.add(villageId)) {
            setDirty();
        }
    }

    public boolean hasGeneratedLibrary(UUID villageId) {
        return generatedLibraryVillages.contains(villageId);
    }

    public void markLibraryGenerated(UUID villageId) {
        if (generatedLibraryVillages.add(villageId)) {
            setDirty();
        }
    }

    public boolean hasGeneratedLumbermill(UUID villageId) {
        return generatedLumbermillVillages.contains(villageId);
    }

    public void markLumbermillGenerated(UUID villageId) {
        if (generatedLumbermillVillages.add(villageId)) {
            setDirty();
        }
    }

    public boolean hasGeneratedLumbermillStructure(long structureKey) {
        return generatedLumbermillStructures.contains(structureKey);
    }

    public void markLumbermillStructureGenerated(long structureKey) {
        if (generatedLumbermillStructures.add(structureKey)) {
            setDirty();
        }
    }

    /** Records a manually placed abandoned-village vault for structure-map searches. */
    public void markAbandonedVaultPosition(BlockPos vaultPosition) {
        if (abandonedVaultPositions.add(vaultPosition.asLong())) {
            setDirty();
        }
    }

    /** Returns persisted runtime-placed vault positions without exposing mutable state. */
    public List<BlockPos> getAbandonedVaultPositions() {
        return abandonedVaultPositions.stream().map(BlockPos::of).toList();
    }

    /** Records the one bank registered for a village. */
    public void registerBankPosition(UUID villageId, BlockPos bankPos) {
        BlockPos immutablePos = bankPos.immutable();
        if (!immutablePos.equals(bankPositions.put(villageId, immutablePos))) {
            setDirty();
        }
    }

    /** Clears a bank link only when it still refers to the specified bank. */
    public void deregisterBankPosition(UUID villageId, BlockPos bankPos) {
        if (bankPositions.remove(villageId, bankPos)) {
            setDirty();
        }
    }

    @Nullable
    public BlockPos getBankPos(UUID villageId) {
        return bankPositions.get(villageId);
    }

    // Pending manager placement queue

    public void addPendingManagerPlacement(UUID villageId, BoundingBox structureBox) {
        pendingManagerPlacements.add(new PendingManagerPlacement(villageId, structureBox));
        setDirty();
    }

    public List<PendingManagerPlacement> getPendingManagerPlacements() {
        return Collections.unmodifiableList(pendingManagerPlacements);
    }

    public void removePendingManagerPlacement(PendingManagerPlacement placement) {
        pendingManagerPlacements.remove(placement);
        setDirty();
    }

    // Accessor methods

    /**
     * Returns the village with the given ID, creating a new one at the given
     * bell position if none exists yet.
     */
    public VillageRecord getOrCreateVillage(UUID villageId, BlockPos bellPos, AABB bounds) {
        return getOrCreateVillage(villageId, bellPos, bounds, VillageType.PLAINS);
    }

    /** Creates a village with the palette captured from its vanilla structure pieces. */
    public VillageRecord getOrCreateVillage(UUID villageId, BlockPos bellPos, AABB bounds,
                                             VillageType villageType) {
        Objects.requireNonNull(villageId, "villageId");
        Objects.requireNonNull(bellPos, "bellPos");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(villageType, "villageType");
        return villages.computeIfAbsent(villageId, id -> {
            VillageRecord record = new VillageRecord(id, bellPos, bounds, villageType);
            setDirty();
            return record;
        });
    }

    /** Creates a generated village and selects its persisted color exactly once. */
    public VillageRecord getOrCreateVillage(UUID villageId, BlockPos bellPos, AABB bounds,
                                             VillageType villageType, RandomSource random) {
        Objects.requireNonNull(villageId, "villageId");
        Objects.requireNonNull(bellPos, "bellPos");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(villageType, "villageType");
        Objects.requireNonNull(random, "random");
        return villages.computeIfAbsent(villageId, id -> {
            VillageColor villageColor = VillageColor.randomFor(villageType, random);
            VillageRecord record = new VillageRecord(id, bellPos, bounds, villageType, villageColor);
            setDirty();
            return record;
        });
    }


    /** Assigns the persisted fallback name without runtime notification (codec/unit-test boundary). */
    public void assignLegacyVillageNumberName(VillageRecord village) {
        assignLegacyVillageNumberName(null, village);
    }

    /** Assigns the persisted fallback name and notifies loaded runtime consumers. */
    public void assignLegacyVillageNumberName(@Nullable ServerLevel level, VillageRecord village) {
        Objects.requireNonNull(village, "village");
        String currentName = village.getName();
        if (!currentName.isBlank() && !"Village".equals(currentName)) {
            return;
        }
        if (nextVillageNumber >= MAX_PERSISTED_VILLAGE_NUMBER) {
            throw new IllegalStateException("Village name sequence exhausted");
        }
        String newName = "Village " + nextVillageNumber;
        if (level == null) {
            village.setName(newName);
            setDirty();
        } else {
            renameVillage(level, village, newName);
        }
        nextVillageNumber++;
        setDirty();
    }

    /**
     * The production mutation boundary for village names. Keeps persistence,
     * POI snapshots, and derived villager names synchronized.
     *
     * @return whether the name actually changed
     */
    public boolean renameVillage(ServerLevel level, VillageRecord village, String newName) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(village, "village");
        Objects.requireNonNull(newName, "newName");
        if (newName.isBlank()) {
            throw new IllegalArgumentException("Village name cannot be blank");
        }
        if (Objects.equals(village.getName(), newName)) {
            return false;
        }
        village.setName(newName);
        setDirty();
        VillagePOIDataCache.invalidateVillage(village.getVillageId());
        VillagerNameRefreshScheduler.requestVillageRefresh(level, village.getVillageId());
        return true;
    }

    /**
     * Registers a villager POI record in the specified village.
     */
    public void registerVillager(UUID villageId, VillagerPOIRecord record) {
        VillageRecord village = villages.get(villageId);
        if (village != null) {
            village.addMember(record);
            setDirty();
        }
    }

    /**
     * Removes a villager from the specified village.
     */
    public void removeVillager(UUID villageId, UUID villagerUUID) {
        VillageRecord village = villages.get(villageId);
        if (village != null) {
            village.removeMember(villagerUUID);
            setDirty();
        }
    }

    /**
     * Returns the nearest bank-backed village bell among the villages whose
     * bounding boxes contain the given position. When an automatically-created
     * record overlaps a bank-backed record, the bank-backed record owns the
     * lookup; otherwise the nearest bell is used. Hash-map iteration order must
     * not decide which village owns an overlapping position.
     */
    @Nullable
    public VillageRecord getVillageFor(BlockPos pos) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        VillageRecord nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        UUID nearestId = null;
        boolean nearestHasBank = false;
        for (VillageRecord village : villages.values()) {
            if (!village.getBoundingBox().contains(x, y, z)) {
                continue;
            }
            boolean hasBank = bankPositions.containsKey(village.getVillageId());
            double distance = village.getBellPosition().distSqr(pos);
            UUID villageId = village.getVillageId();
            if (nearest == null
                    || (hasBank && !nearestHasBank)
                    || (hasBank == nearestHasBank
                    && (distance < nearestDistance
                    || (Double.compare(distance, nearestDistance) == 0
                    && (nearestId == null || villageId.toString().compareTo(nearestId.toString()) < 0))))) {
                nearest = village;
                nearestDistance = distance;
                nearestId = villageId;
                nearestHasBank = hasBank;
            }
        }
        return nearest;
    }

    /**
     * Returns the village whose bell position is closest to the given position.
     * Prefers villages whose bounding box contains the position; if multiple do,
     * picks the one with the nearest bell. Falls back to the globally nearest
     * bell if the position is outside all bounding boxes.
     */
    @Nullable
    public VillageRecord getNearestVillage(BlockPos pos) {
        if (villages.isEmpty()) {
            return null;
        }

        VillageRecord bestContaining = null;
        double bestContainingDist = Double.MAX_VALUE;
        VillageRecord bestGlobal = null;
        double bestGlobalDist = Double.MAX_VALUE;

        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;

        for (VillageRecord village : villages.values()) {
            double dist = village.getBellPosition().distSqr(pos);

            if (village.getBoundingBox().contains(x, y, z)) {
                if (dist < bestContainingDist
                        || (Double.compare(dist, bestContainingDist) == 0
                        && isEarlierVillage(village, bestContaining))) {
                    bestContainingDist = dist;
                    bestContaining = village;
                }
            }

            if (dist < bestGlobalDist
                    || (Double.compare(dist, bestGlobalDist) == 0
                    && isEarlierVillage(village, bestGlobal))) {
                bestGlobalDist = dist;
                bestGlobal = village;
            }
        }

        return bestContaining != null ? bestContaining : bestGlobal;
    }

    private static boolean isEarlierVillage(VillageRecord candidate, @Nullable VillageRecord current) {
        return current == null
                || candidate.getVillageId().toString().compareTo(current.getVillageId().toString()) < 0;
    }

    /**
     * Returns an immutable snapshot of the village with the given ID,
     * or null if no such village exists.
     */
    @Nullable
    public Map<UUID, VillagerPOIRecord> getSnapshot(UUID villageId) {
        VillageRecord village = villages.get(villageId);
        if (village == null) {
            return null;
        }
        return village.getMembersSnapshot();
    }

    /**
     * Returns an unmodifiable view of all villages.
     */
    public Map<UUID, VillageRecord> getVillages() {
        return Collections.unmodifiableMap(villages);
    }

    // Village Manager position registry (transient)

    /**
     * Registers the position of a {@code VillageManagerBlockEntity} for the given village.
     * Called from {@code VillageManagerBlockEntity.onLoad()} and on initial placement.
     * This map is transient and not persisted to disk.
     */
    public void registerVillageManager(UUID villageId, BlockPos pos) {
        vmPositions.put(villageId, pos.immutable());
    }

    /**
     * Removes the VM position entry for the given village. Called when the
     * {@code VillageManagerBlockEntity} is removed from the world.
     */
    public void deregisterVillageManager(UUID villageId) {
        vmPositions.remove(villageId);
    }

    /** Removes a manager only when the registry still points at that manager's position. */
    public void deregisterVillageManager(UUID villageId, BlockPos managerPos) {
        vmPositions.remove(villageId, managerPos);
    }

    /**
     * Returns the last-registered position of the {@code VillageManagerBlockEntity}
     * for the given village, or {@code null} if none is registered.
     */
    @Nullable
    public BlockPos getVMPos(UUID villageId) {
        return vmPositions.get(villageId);
    }

    // Reset

    /**
     * Clears all village data, processed chunk markers, and pending placements.
     * Used by the reset command.
     */
    public void clearAll() {
        villages.clear();
        processedStartChunks.clear();
        generatedBankVillages.clear();
        generatedLibraryVillages.clear();
        generatedLumbermillVillages.clear();
        generatedLumbermillStructures.clear();
        abandonedVaultPositions.clear();
        bankPositions.clear();
        pendingManagerPlacements.clear();
        vmPositions.clear();
        setDirty();
    }
}
