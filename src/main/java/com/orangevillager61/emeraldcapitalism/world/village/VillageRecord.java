package com.orangevillager61.emeraldcapitalism.world.village;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.VillageManagerBlock;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import com.orangevillager61.emeraldcapitalism.world.village.scan.AdaptiveChunkScanPlan;
import com.orangevillager61.emeraldcapitalism.world.village.scan.InitialVillageScanChunkLoadPool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Represents a single village: its bell position, bounding box, and member villagers.
 * Bed and job-site caches are verified incrementally after the initial scan; block
 * events and bounding-box expansions add new positions. The initial scan also
 * tightens the bounds around discovered village blocks.
 */
public class VillageRecord {

    /** Number of blocks added on each side when shrinking the box to fit: one chunk. */
    private static final int BOUNDARY_MARGIN = 16;
    private static final int INITIAL_SCAN_PREFETCH_WINDOW = 4;

    /** Default welcome message shown when a player enters the village. */
    public static final String DEFAULT_WELCOME_MESSAGE = "Welcome to our village!";
    /** Number of ticks banks wait before reacting to a newly registered candidate. */
    public static final long GOVERNOR_CANDIDATE_ATTACK_GRACE_TICKS = 1_000L;
    static final int MAX_PERSISTED_MEMBERS = 4_096;
    static final int MAX_PERSISTED_OPINION_MODIFIERS = 4_096;
    static final int MAX_PERSISTED_GOVERNOR_CANDIDATES = 1;
    static final int MAX_PERSISTED_FARMLAND_POSITIONS = 65_536;
    public static final int MAX_PERSISTED_DOOR_POSITIONS = 65_536;
    private static final int MAX_PERSISTED_NAMING_PAIRS = 400;
    private static final int MAX_NAMING_ELEMENT_LENGTH = 64;
    private static final int MAX_NAMING_PAIR_LENGTH = MAX_NAMING_ELEMENT_LENGTH * 2 + 1;
    private static final int MAX_NAMING_BIOME_LENGTH = 64;
    private static final int MAX_DRIFT_RULE_LENGTH = 3;
    private static final int MAX_VILLAGER_NAMING_DRIFT_RULES = 2;

    private static final Codec<String> VILLAGE_NAME_CODEC = boundedStringCodec(
            ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH, "Village name").validate(name ->
            name.isBlank()
                    ? DataResult.error(() -> "Village name cannot be blank")
                    : DataResult.success(name));
    private static final Codec<String> NAMING_BIOME_CODEC = boundedStringCodec(
            MAX_NAMING_BIOME_LENGTH, "Villager naming biome");
    private static final Codec<String> WELCOME_MESSAGE_CODEC = boundedStringCodec(
            ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH, "Village welcome message");

    private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private static final Codec<Bounds> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("min_x").forGetter(Bounds::minX),
                Codec.DOUBLE.fieldOf("min_y").forGetter(Bounds::minY),
                Codec.DOUBLE.fieldOf("min_z").forGetter(Bounds::minZ),
                Codec.DOUBLE.fieldOf("max_x").forGetter(Bounds::maxX),
                Codec.DOUBLE.fieldOf("max_y").forGetter(Bounds::maxY),
                Codec.DOUBLE.fieldOf("max_z").forGetter(Bounds::maxZ)
        ).apply(instance, Bounds::new));
    }

    private static final Codec<AABB> AABB_CODEC = Bounds.CODEC.flatXmap(
            bounds -> {
                if (!Double.isFinite(bounds.minX()) || !Double.isFinite(bounds.minY())
                        || !Double.isFinite(bounds.minZ()) || !Double.isFinite(bounds.maxX())
                        || !Double.isFinite(bounds.maxY()) || !Double.isFinite(bounds.maxZ())) {
                    return DataResult.error(() -> "AABB coordinates must be finite");
                }
                return DataResult.success(new AABB(
                        Math.min(bounds.minX(), bounds.maxX()),
                        Math.min(bounds.minY(), bounds.maxY()),
                        Math.min(bounds.minZ(), bounds.maxZ()),
                        Math.max(bounds.minX(), bounds.maxX()),
                        Math.max(bounds.minY(), bounds.maxY()),
                        Math.max(bounds.minZ(), bounds.maxZ())
                ));
            },
            box -> DataResult.success(new Bounds(
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ
            ))
    );

    private static final Codec<List<String>> VILLAGER_NAMING_DRIFT_RULES_CODEC =
            boundedStringCodec(MAX_DRIFT_RULE_LENGTH, "Villager drift rule")
                    .sizeLimitedListOf(MAX_VILLAGER_NAMING_DRIFT_RULES).comapFlatMap(values -> {
                        if (values.size() > MAX_VILLAGER_NAMING_DRIFT_RULES) {
                            return DataResult.error(() -> "A village may persist at most two villager drift rules");
                        }
                        if (values.stream().anyMatch(rule -> !isValidDriftRule(rule))) {
                            return DataResult.error(() -> "A village may persist only D1 through D12");
                        }
                        if (new HashSet<>(values).size() != values.size()) {
                            return DataResult.error(() -> "A village may not persist duplicate villager drift rules");
                        }
                        return DataResult.success(normalizeDriftRules(values));
                    },
                    values -> values);
    private static final Codec<List<String>> VILLAGER_NAMING_ALLOCATED_PAIRS_CODEC =
            boundedStringCodec(MAX_NAMING_PAIR_LENGTH, "Villager naming pair")
                    .sizeLimitedListOf(MAX_PERSISTED_NAMING_PAIRS)
                    .comapFlatMap(values -> values.size() <= MAX_PERSISTED_NAMING_PAIRS
                    ? DataResult.success(values)
                    : DataResult.error(() -> "A village may persist at most "
                    + MAX_PERSISTED_NAMING_PAIRS + " villager naming pairs"),
                    values -> values);

    private record OpinionModifier(UUID playerId, int modifier) {
        private static final Codec<OpinionModifier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("player_id").forGetter(OpinionModifier::playerId),
                Codec.INT.fieldOf("modifier").forGetter(OpinionModifier::modifier)
        ).apply(instance, OpinionModifier::new));
    }

    private static final Codec<List<UUID>> GOVERNOR_CANDIDATES_CODEC =
            UUIDUtil.CODEC.sizeLimitedListOf(MAX_PERSISTED_GOVERNOR_CANDIDATES);

    /** Keeps the rest of a village usable when one persisted member is corrupt. */
    private static final Codec<List<VillagerPOIRecord>> MEMBERS_CODEC = Codec.of(
            new Encoder<>() {
                @Override
                public <T> DataResult<T> encode(List<VillagerPOIRecord> input, DynamicOps<T> ops, T prefix) {
                    return VillagerPOIRecord.CODEC.sizeLimitedListOf(MAX_PERSISTED_MEMBERS)
                            .encode(input, ops, prefix);
                }
            },
            new Decoder<>() {
                @Override
                public <T> DataResult<Pair<List<VillagerPOIRecord>, T>> decode(DynamicOps<T> ops, T input) {
                    return ops.getStream(input).flatMap(elements -> {
                        List<T> encodedMembers = elements.limit((long) MAX_PERSISTED_MEMBERS + 1).toList();
                        if (encodedMembers.size() > MAX_PERSISTED_MEMBERS) {
                            return DataResult.error(() -> "Village record exceeds "
                                    + MAX_PERSISTED_MEMBERS + " persisted members");
                        }
                        List<VillagerPOIRecord> members = new ArrayList<>();
                        for (T element : encodedMembers) {
                            VillagerPOIRecord.CODEC.parse(ops, element)
                                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                                        "[ECAP] Skipping corrupt villager POI record: {}", message))
                                .ifPresent(members::add);
                        }
                        return DataResult.success(Pair.of(members, ops.empty()));
                    });
                }
            }
    );

    private static final Codec<List<BlockPos>> DOOR_POSITIONS_CODEC =
            BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_DOOR_POSITIONS);

    /** Codec for the durable portion of a village record, excluding the extra door field. */
    private static final Codec<VillageRecord> BASE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("village_id").forGetter(VillageRecord::getVillageId),
            BlockPos.CODEC.fieldOf("bell_position").forGetter(VillageRecord::getBellPosition),
            AABB_CODEC.fieldOf("bounding_box").forGetter(VillageRecord::getBoundingBox),
            AABB_CODEC.optionalFieldOf("initial_scan_anchor_bounds")
                    .forGetter(record -> Optional.ofNullable(record.initialScanAnchorBounds)),
            VILLAGE_NAME_CODEC.optionalFieldOf("name", "Village").forGetter(record -> record.name),
            NAMING_BIOME_CODEC.optionalFieldOf("villager_naming_biome", "plains")
                    .forGetter(record -> record.villagerNamingBiome),
            VILLAGER_NAMING_DRIFT_RULES_CODEC.optionalFieldOf("villager_naming_drift_rules", List.of())
                    .forGetter(record -> new ArrayList<>(record.villagerNamingDriftRules)),
            VILLAGER_NAMING_ALLOCATED_PAIRS_CODEC.optionalFieldOf("villager_naming_allocated_pairs", List.of())
                    .forGetter(record -> new ArrayList<>(record.villagerNamingAllocatedPairs)),
            MEMBERS_CODEC.optionalFieldOf("members", List.of())
                    .forGetter(record -> new ArrayList<>(record.members.values())),
            WELCOME_MESSAGE_CODEC.optionalFieldOf("welcome_message", DEFAULT_WELCOME_MESSAGE)
                    .forGetter(record -> record.welcomeMessage),
            Codec.BOOL.optionalFieldOf("abandoned_village", false)
                    .forGetter(VillageRecord::isAbandonedVillage),
            OpinionModifier.CODEC.sizeLimitedListOf(MAX_PERSISTED_OPINION_MODIFIERS)
                    .optionalFieldOf("opinion_modifiers", List.of())
                    .forGetter(VillageRecord::opinionModifiers),
            UUIDUtil.CODEC.optionalFieldOf("governor_id")
                    .forGetter(record -> Optional.ofNullable(record.governorId)),
            GOVERNOR_CANDIDATES_CODEC.optionalFieldOf("governor_candidates", List.of())
                    .forGetter(record -> record.governorCandidateId == null
                            ? List.of() : List.of(record.governorCandidateId)),
            BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_FARMLAND_POSITIONS)
                    .optionalFieldOf("farmland_registry", List.of())
                    .forGetter(record -> new ArrayList<>(record.farmlandRegistry)),
            BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_FARMLAND_POSITIONS)
                    .optionalFieldOf("repair_queue", List.of())
                    .forGetter(record -> new ArrayList<>(record.repairQueue))
            ).apply(instance, (villageId, bellPosition, boundingBox, initialScanAnchorBounds, name,
                       villagerNamingBiome, villagerNamingDriftRules, villagerNamingAllocatedPairs,
                       members, welcomeMessage, abandonedVillage, opinionModifiers, governorId,
                       governorCandidates, farmlandRegistry, repairQueue) -> fromCodec(
            villageId, bellPosition, boundingBox, initialScanAnchorBounds, name, villagerNamingBiome,
            villagerNamingDriftRules, villagerNamingAllocatedPairs, members, welcomeMessage,
            abandonedVillage, opinionModifiers, governorId, governorCandidates, farmlandRegistry,
            List.of(), List.of(), repairQueue)));

    /** Adds the door registry as a flat top-level field without exceeding the codec arity limit. */
    public static final Codec<VillageRecord> CODEC = Codec.of(
            new Encoder<>() {
                @Override
                public <T> DataResult<T> encode(VillageRecord input, DynamicOps<T> ops, T prefix) {
                    return BASE_CODEC.encode(input, ops, prefix).flatMap(encoded -> {
                        List<T> positions = new ArrayList<>(input.doorRegistry.size());
                        for (BlockPos pos : input.doorRegistry) {
                            DataResult<T> encodedPos = BlockPos.CODEC.encodeStart(ops, pos);
                            if (encodedPos.error().isPresent()) {
                                return DataResult.error(() -> encodedPos.error().orElseThrow().message());
                            }
                            positions.add(encodedPos.result().orElseThrow());
                        }
                        return ops.mergeToMap(encoded, ops.createString("door_registry"),
                                ops.createList(positions.stream()))
                                .flatMap(withDoors -> {
                                    List<T> missingPositions = new ArrayList<>(input.missingDoorRegistry.size());
                                    for (BlockPos pos : input.missingDoorRegistry) {
                                        DataResult<T> encodedPos = BlockPos.CODEC.encodeStart(ops, pos);
                                        if (encodedPos.error().isPresent()) {
                                            return DataResult.error(() -> encodedPos.error().orElseThrow().message());
                                        }
                                        missingPositions.add(encodedPos.result().orElseThrow());
                                    }
                                    return ops.mergeToMap(withDoors, ops.createString("missing_door_registry"),
                                            ops.createList(missingPositions.stream()));
                                })
                                .flatMap(withMissingDoors -> ops.mergeToMap(withMissingDoors,
                                        ops.createString("farmland_repair_enabled"),
                                        ops.createBoolean(input.farmlandRepairEnabled)))
                                .flatMap(withFarmlandSetting -> ops.mergeToMap(withFarmlandSetting,
                                        ops.createString("door_repair_enabled"),
                                        ops.createBoolean(input.doorRepairEnabled)))
                                .flatMap(withDoorSetting -> ops.mergeToMap(withDoorSetting,
                                        ops.createString("governor_candidate_attack_grace_until"),
                                        ops.createLong(input.governorCandidateAttackGraceUntil)))
                                .flatMap(withGraceUntil -> mergeOptionalUuid(ops, withGraceUntil,
                                        "governor_candidate_attack_player",
                                        input.governorCandidateAttackPlayerId))
                                .flatMap(withGracePlayer -> mergeOptionalBlockPos(ops, withGracePlayer,
                                        "governor_candidate_attack_bank",
                                        input.governorCandidateAttackBankPos))
                                .flatMap(withGraceBank -> mergeOptionalUuid(ops, withGraceBank,
                                        "governor_candidate_attack_mayor",
                                        input.governorCandidateAttackMayorId));
                    });
                }
            },
            new Decoder<>() {
                @Override
                public <T> DataResult<Pair<VillageRecord, T>> decode(DynamicOps<T> ops, T input) {
                    return BASE_CODEC.decode(ops, input).flatMap(decoded ->
                            ops.getMap(input).flatMap(map -> {
                                T encodedDoors = map.get(ops.createString("door_registry"));
                                DataResult<List<BlockPos>> doorsResult = encodedDoors == null
                                        ? DataResult.success(List.of())
                                        : DOOR_POSITIONS_CODEC.parse(ops, encodedDoors);
                                T encodedMissingDoors = map.get(ops.createString("missing_door_registry"));
                                DataResult<List<BlockPos>> missingDoorsResult = encodedMissingDoors == null
                                        ? DataResult.success(List.of())
                                        : DOOR_POSITIONS_CODEC.parse(ops, encodedMissingDoors);
                                return doorsResult.flatMap(doors -> missingDoorsResult.flatMap(missingDoors ->
                                        readOptionalBoolean(ops, map, "farmland_repair_enabled",
                                                decoded.getFirst().farmlandRepairEnabled).flatMap(farmlandEnabled ->
                                                readOptionalBoolean(ops, map, "door_repair_enabled",
                                                        decoded.getFirst().doorRepairEnabled).flatMap(doorEnabled ->
                                                        readOptionalLong(ops, map,
                                                                "governor_candidate_attack_grace_until",
                                                                decoded.getFirst().governorCandidateAttackGraceUntil)
                                                                .flatMap(graceUntil ->
                                                                        readOptionalUuid(ops, map,
                                                                                "governor_candidate_attack_player")
                                                                                .flatMap(gracePlayer ->
                                                                                        readOptionalBlockPos(ops, map,
                                                                                                "governor_candidate_attack_bank")
                                                                                                .flatMap(graceBank ->
                                                                                                        readOptionalUuid(ops, map,
                                                                                                                "governor_candidate_attack_mayor")
                                                                                                                .map(graceMayor -> {
                                                    decoded.getFirst().doorRegistry.addAll(doors);
                                                    decoded.getFirst().missingDoorRegistry.addAll(missingDoors);
                                                    decoded.getFirst().missingDoorRegistry.removeAll(
                                                            decoded.getFirst().doorRegistry);
                                                    decoded.getFirst().farmlandRepairEnabled = farmlandEnabled;
                                                    decoded.getFirst().doorRepairEnabled = doorEnabled;
                                                    UUID candidateId = decoded.getFirst().governorCandidateId;
                                                    UUID gracePlayerId = gracePlayer.orElse(null);
                                                    BlockPos graceBankPos = graceBank.orElse(null);
                                                    UUID graceMayorId = graceMayor.orElse(null);
                                                    boolean completeGrace = candidateId != null
                                                            && candidateId.equals(gracePlayerId)
                                                            && graceBankPos != null
                                                            && graceMayorId != null;
                                                    decoded.getFirst().governorCandidateAttackPlayerId =
                                                            completeGrace ? gracePlayerId : null;
                                                    decoded.getFirst().governorCandidateAttackBankPos =
                                                            completeGrace ? graceBankPos : null;
                                                    decoded.getFirst().governorCandidateAttackMayorId =
                                                            completeGrace ? graceMayorId : null;
                                                    decoded.getFirst().governorCandidateAttackGraceUntil =
                                                            completeGrace ? graceUntil : 0L;
                                                    return decoded;
                                                }))));
                            }));
                }
            }
    );

    private static Codec<String> boundedStringCodec(int maxLength, String description) {
        return Codec.STRING.validate(value -> value.length() <= maxLength
                ? DataResult.success(value)
                : DataResult.error(() -> description + " exceeds " + maxLength + " characters"));
    }

    private static <T> DataResult<T> mergeOptionalUuid(DynamicOps<T> ops, T input, String key,
                                                       @Nullable UUID value) {
        if (value == null) {
            return DataResult.success(input);
        }
        return UUIDUtil.CODEC.encodeStart(ops, value)
                .flatMap(encoded -> ops.mergeToMap(input, ops.createString(key), encoded));
    }

    private static <T> DataResult<T> mergeOptionalBlockPos(DynamicOps<T> ops, T input, String key,
                                                           @Nullable BlockPos value) {
        if (value == null) {
            return DataResult.success(input);
        }
        return BlockPos.CODEC.encodeStart(ops, value)
                .flatMap(encoded -> ops.mergeToMap(input, ops.createString(key), encoded));
    }

    private final UUID villageId;
    private String name;
    /** Stable substrate region used for personal pools and profession bynames. */
    private String villagerNamingBiome = "plains";
    /** Drift IDs are assigned once at founding and never recomputed on reload. */
    private final List<String> villagerNamingDriftRules = new ArrayList<>();
    /** Pair keys reserved in this village until its substrate pool is exhausted. */
    private final Set<String> villagerNamingAllocatedPairs = new HashSet<>();
    private BlockPos bellPosition;
    private AABB boundingBox;
    /** Known generated structure extent that an adaptive initial scan must cover before pruning directions. */
    private AABB initialScanAnchorBounds;
    private final Map<UUID, VillagerPOIRecord> members = new HashMap<>();
    /** Persistent per-player modifiers applied on top of the average villager reputation. */
    private final Map<UUID, Integer> opinionModifiers = new HashMap<>();
    /** The one player currently holding the governor role, if the village has appointed one. */
    @Nullable
    private UUID governorId;
    /** The one player currently standing as governor candidate, if any. */
    @Nullable
    private UUID governorCandidateId;
    /** Candidate UUID associated with the bank/mayor attack grace tuple. */
    @Nullable
    private UUID governorCandidateAttackPlayerId;
    /** Bank position associated with the bank/mayor attack grace tuple. */
    @Nullable
    private BlockPos governorCandidateAttackBankPos;
    /** Mayor UUID associated with the bank/mayor attack grace tuple. */
    @Nullable
    private UUID governorCandidateAttackMayorId;
    /** Absolute game-time tick at which bank hostility toward the candidate may begin. */
    private long governorCandidateAttackGraceUntil;

    /** True when this village was generated from vanilla's zombie/abandoned pools. */
    private boolean abandonedVillage;

    /** Welcome message shown when a player enters the village bounding box. Empty string = disabled. */
    private String welcomeMessage = DEFAULT_WELCOME_MESSAGE;

    // Cached block positions

    /** Cached bed HEAD positions inside the bounding box. */
    private final Set<BlockPos> cachedBedPositions = new HashSet<>();
    /** Cached job-site block positions inside the bounding box. */
    private final Map<BlockPos, String> cachedJobSitePositions = new HashMap<>();
    /** Whether a full scan has been performed at least once. */
    private boolean cacheInitialized = false;

    // Farmland tracking

    /** All farmland positions within the village bounding box. */
    private final Set<BlockPos> farmlandRegistry = new HashSet<>();
    /** Lower-half positions of all doors within the village bounding box. */
    private final Set<BlockPos> doorRegistry = new HashSet<>();
    /** Previously tracked door positions that are currently empty and need repair. */
    private final Set<BlockPos> missingDoorRegistry = new HashSet<>();
    /** Farmland positions that need repair (trampled or turned to dirt). */
    private final Set<BlockPos> repairQueue = new HashSet<>();
    /** Farmland positions currently claimed by a farmer for repair. Cleared on server load. */
    private final Set<BlockPos> claimedPositions = new HashSet<>();
    /** Whether farmer repair work is enabled for this village. */
    private boolean farmlandRepairEnabled = Config.enableFarmlandRepair;
    /** Whether door cache repair/tracking is enabled for this village. */
    private boolean doorRepairEnabled = true;

    /** Transient cursor and replacement caches for a budgeted full scan. */
    private FullScanState fullScanState;
    /** True until the manager has refreshed villagers and published the completed scan. */
    private boolean fullScanCompletionPending;

    private static final class FullScanState {
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
        private final AdaptiveChunkScanPlan adaptivePlan;
        private final Set<AdaptiveChunkScanPlan.ChunkCoordinate> knownInterestingChunks;
        private int chunkX, chunkZ;
        private int x, y, z;
        private boolean currentChunkInteresting;
        private final Map<Integer, Boolean> currentChunkSectionCandidates = new HashMap<>();
        /** True when this adaptive pass had to skip a chunk that was not available. */
        private boolean encounteredUnavailableChunk;
        private final Set<BlockPos> beds = new HashSet<>();
        private final Map<BlockPos, String> jobSites = new HashMap<>();
        private final Set<BlockPos> farmland = new HashSet<>();
        private final Set<BlockPos> doors = new HashSet<>();

        private FullScanState(
                AABB area,
                BlockPos bell,
                AABB requiredBounds,
                Collection<BlockPos> knownFarmland,
                Collection<BlockPos> knownDoors,
                boolean adaptive
        ) {
            minX = (int) Math.floor(area.minX);
            minY = (int) Math.floor(area.minY);
            minZ = (int) Math.floor(area.minZ);
            maxX = (int) Math.floor(area.maxX);
            maxY = (int) Math.floor(area.maxY);
            maxZ = (int) Math.floor(area.maxZ);
            minChunkX = minX >> 4;
            maxChunkX = maxX >> 4;
            minChunkZ = minZ >> 4;
            maxChunkZ = maxZ >> 4;

            if (adaptive) {
                Set<AdaptiveChunkScanPlan.ChunkCoordinate> requiredChunks = new HashSet<>();
                addRequiredBounds(requiredChunks, requiredBounds);
                knownInterestingChunks = new HashSet<>();
                for (BlockPos farmlandPos : knownFarmland) {
                    AdaptiveChunkScanPlan.ChunkCoordinate chunk = new AdaptiveChunkScanPlan.ChunkCoordinate(
                            farmlandPos.getX() >> 4,
                            farmlandPos.getZ() >> 4
                    );
                    requiredChunks.add(chunk);
                    knownInterestingChunks.add(chunk);
                }
                for (BlockPos doorPos : knownDoors) {
                    AdaptiveChunkScanPlan.ChunkCoordinate chunk = new AdaptiveChunkScanPlan.ChunkCoordinate(
                            doorPos.getX() >> 4,
                            doorPos.getZ() >> 4
                    );
                    requiredChunks.add(chunk);
                    knownInterestingChunks.add(chunk);
                }
                adaptivePlan = new AdaptiveChunkScanPlan(
                        bell.getX() >> 4,
                        bell.getZ() >> 4,
                        minChunkX,
                        maxChunkX,
                        minChunkZ,
                        maxChunkZ,
                        requiredChunks
                );
                setCurrentChunk(adaptivePlan.currentChunk());
            } else {
                adaptivePlan = null;
                knownInterestingChunks = Set.of();
                chunkX = minChunkX;
                chunkZ = minChunkZ;
                resetBlockCursor();
            }
        }

        private static void addRequiredBounds(
                Set<AdaptiveChunkScanPlan.ChunkCoordinate> requiredChunks,
                AABB requiredBounds
        ) {
            if (requiredBounds == null) {
                return;
            }
            int requiredMinChunkX = (int) Math.floor(requiredBounds.minX) >> 4;
            int requiredMaxChunkX = (int) Math.floor(requiredBounds.maxX) >> 4;
            int requiredMinChunkZ = (int) Math.floor(requiredBounds.minZ) >> 4;
            int requiredMaxChunkZ = (int) Math.floor(requiredBounds.maxZ) >> 4;
            for (int requiredChunkX = requiredMinChunkX; requiredChunkX <= requiredMaxChunkX; requiredChunkX++) {
                for (int requiredChunkZ = requiredMinChunkZ; requiredChunkZ <= requiredMaxChunkZ; requiredChunkZ++) {
                    requiredChunks.add(new AdaptiveChunkScanPlan.ChunkCoordinate(requiredChunkX, requiredChunkZ));
                }
            }
        }

        private void setCurrentChunk(AdaptiveChunkScanPlan.ChunkCoordinate chunk) {
            chunkX = chunk.x();
            chunkZ = chunk.z();
            currentChunkInteresting = knownInterestingChunks.contains(chunk);
            currentChunkSectionCandidates.clear();
            resetBlockCursor();
        }

        private void resetBlockCursor() {
            x = currentMinX();
            y = minY;
            z = currentMinZ();
        }

        private boolean advance() {
            if (++y > maxY) {
                y = minY;
                if (++x > currentMaxX()) {
                    x = currentMinX();
                    if (++z > currentMaxZ()) {
                        return advanceChunk();
                    }
                }
            }
            return false;
        }

        /** Advances past the remainder of the current unloaded chunk column. */
        private boolean skipUnloadedChunkColumn() {
            return advanceChunk(currentChunkInteresting
                    ? AdaptiveChunkScanPlan.ChunkOutcome.INTERESTING
                    : AdaptiveChunkScanPlan.ChunkOutcome.UNKNOWN);
        }

        /** Skips the current 16-block-high section slice for one horizontal block column. */
        private boolean skipCurrentSectionColumn() {
            y = Math.min(maxY, ((y >> 4) << 4) + 15);
            return advance();
        }

        private boolean currentSectionMayContainCandidates(LevelChunk chunk) {
            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return false;
            }
            return currentChunkSectionCandidates.computeIfAbsent(
                    sectionIndex,
                    ignored -> sections[sectionIndex].maybeHas(VillageRecord::isFullScanCandidate)
            );
        }

        /** Section palettes may change between server ticks, so false results live for one slice. */
        private void beginSlice() {
            currentChunkSectionCandidates.clear();
        }

        private List<AdaptiveChunkScanPlan.ChunkCoordinate> upcomingChunks(int limit) {
            return adaptivePlan == null ? List.of() : adaptivePlan.upcomingChunks(limit);
        }

        private boolean advanceChunk() {
            return advanceChunk(currentChunkInteresting
                    ? AdaptiveChunkScanPlan.ChunkOutcome.INTERESTING
                    : AdaptiveChunkScanPlan.ChunkOutcome.EMPTY);
        }

        private boolean advanceChunk(AdaptiveChunkScanPlan.ChunkOutcome outcome) {
            if (adaptivePlan != null) {
                adaptivePlan.completeCurrentChunk(outcome);
                if (adaptivePlan.isComplete()) {
                    return true;
                }
                setCurrentChunk(adaptivePlan.currentChunk());
                return false;
            }

            if (++chunkX > maxChunkX) {
                chunkX = minChunkX;
                chunkZ++;
            }
            if (chunkZ > maxChunkZ) {
                return true;
            }
            currentChunkSectionCandidates.clear();
            resetBlockCursor();
            return false;
        }

        private void markCurrentChunkInteresting() {
            currentChunkInteresting = true;
        }

        private int currentMinX() {
            return Math.max(minX, chunkX << 4);
        }

        private int currentMaxX() {
            return Math.min(maxX, (chunkX << 4) + 15);
        }

        private int currentMinZ() {
            return Math.max(minZ, chunkZ << 4);
        }

        private int currentMaxZ() {
            return Math.min(maxZ, (chunkZ << 4) + 15);
        }
    }

    public VillageRecord(UUID villageId, BlockPos bellPosition, AABB boundingBox) {
        this.villageId = villageId;
        this.name = "Village";
        this.bellPosition = bellPosition.immutable();
        this.boundingBox = boundingBox;
    }

    private static VillageRecord fromCodec(
            UUID villageId,
            BlockPos bellPosition,
            AABB boundingBox,
            Optional<AABB> initialScanAnchorBounds,
            String name,
            String villagerNamingBiome,
            List<String> villagerNamingDriftRules,
            List<String> villagerNamingAllocatedPairs,
            List<VillagerPOIRecord> members,
            String welcomeMessage,
            boolean abandonedVillage,
            List<OpinionModifier> opinionModifiers,
            Optional<UUID> governorId,
            List<UUID> governorCandidates,
            List<BlockPos> farmlandRegistry,
            List<BlockPos> doorRegistry,
            List<BlockPos> missingDoorRegistry,
            List<BlockPos> repairQueue
    ) {
        VillageRecord record = new VillageRecord(villageId, bellPosition, boundingBox);
        record.initialScanAnchorBounds = initialScanAnchorBounds.orElse(null);
        record.name = name;
        record.villagerNamingBiome = com.orangevillager61.emeraldcapitalism.world.village.naming.villager.VillagerNamingData
                .normalizeBiome(villagerNamingBiome);
        record.villagerNamingDriftRules.addAll(normalizeDriftRules(villagerNamingDriftRules));
        record.villagerNamingAllocatedPairs.addAll(villagerNamingAllocatedPairs);
        record.welcomeMessage = welcomeMessage;
        record.abandonedVillage = abandonedVillage;
        record.governorId = governorId.orElse(null);
        for (UUID candidateId : governorCandidates) {
            if (!candidateId.equals(record.governorId)) {
                record.governorCandidateId = candidateId;
                break;
            }
        }
        for (VillagerPOIRecord member : members) {
            record.members.put(member.getVillagerUUID(), member);
        }
        for (OpinionModifier opinionModifier : opinionModifiers) {
            if (opinionModifier.modifier() != 0) {
                record.opinionModifiers.put(opinionModifier.playerId(), opinionModifier.modifier());
            }
        }
        record.farmlandRegistry.addAll(farmlandRegistry);
        record.doorRegistry.addAll(doorRegistry);
        record.missingDoorRegistry.addAll(missingDoorRegistry);
        record.repairQueue.addAll(repairQueue);
        return record;
    }

    private List<OpinionModifier> opinionModifiers() {
        List<OpinionModifier> entries = new ArrayList<>(opinionModifiers.size());
        for (Map.Entry<UUID, Integer> entry : opinionModifiers.entrySet()) {
            entries.add(new OpinionModifier(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    // Getters

    public UUID getVillageId() {
        return villageId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        Objects.requireNonNull(name, "Village name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Village name cannot be blank");
        }
        this.name = ProtocolStringLimits.clamp(name, ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
    }

    public String getVillagerNamingBiome() {
        return villagerNamingBiome;
    }

    /** Returns the persisted founding drift IDs, never a recomputed snapshot. */
    public List<String> getVillagerNamingDriftRules() {
        return Collections.unmodifiableList(villagerNamingDriftRules);
    }

    public boolean hasVillagerNamingDriftRules() {
        return !villagerNamingDriftRules.isEmpty();
    }

    public boolean setVillagerNamingState(String biome, Collection<String> driftRules) {
        String normalizedBiome = com.orangevillager61.emeraldcapitalism.world.village.naming.villager.VillagerNamingData
                .normalizeBiome(biome);
        List<String> normalizedDriftRules = normalizeDriftRules(driftRules);
        boolean changed = !Objects.equals(villagerNamingBiome, normalizedBiome)
                || !villagerNamingDriftRules.equals(normalizedDriftRules);
        villagerNamingBiome = normalizedBiome;
        villagerNamingDriftRules.clear();
        villagerNamingDriftRules.addAll(normalizedDriftRules);
        return changed;
    }

    private static List<String> normalizeDriftRules(Collection<String> values) {
        Objects.requireNonNull(values, "driftRules");
        if (values.size() > MAX_VILLAGER_NAMING_DRIFT_RULES) {
            throw new IllegalArgumentException("A village may have at most two villager drift rules");
        }

        Set<String> unique = new HashSet<>();
        List<String> validated = new ArrayList<>(values.size());
        for (String rule : values) {
            Objects.requireNonNull(rule, "driftRules contains null");
            if (!isValidDriftRule(rule)) {
                throw new IllegalArgumentException("Village drift rule must be D1 through D12: " + rule);
            }
            if (!unique.add(rule)) {
                throw new IllegalArgumentException("A village may not contain duplicate drift rules: " + rule);
            }
            validated.add(rule);
        }

        return validated.stream()
                .sorted(Comparator.comparingInt(VillageRecord::driftRuleIndex))
                .toList();
    }

    private static int driftRuleIndex(String rule) {
        return Integer.parseInt(rule.substring(1));
    }

    private static boolean isValidDriftRule(String rule) {
        return rule != null && rule.matches("D(?:[1-9]|1[0-2])");
    }

    /** Reserves a personal element pair while the village pool still has room. */
    public boolean reserveVillagerNamingPair(String first, String second) {
        return villagerNamingAllocatedPairs.add(first + ":" + second);
    }

    public boolean hasVillagerNamingPair(String first, String second) {
        return villagerNamingAllocatedPairs.contains(first + ":" + second);
    }

    public int getVillagerNamingAllocatedPairCount() {
        return villagerNamingAllocatedPairs.size();
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = ProtocolStringLimits.clamp(
                welcomeMessage, ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH);
    }

    public boolean isFarmlandRepairEnabled() {
        return farmlandRepairEnabled;
    }

    public boolean setFarmlandRepairEnabled(boolean enabled) {
        if (farmlandRepairEnabled == enabled) {
            return false;
        }
        farmlandRepairEnabled = enabled;
        if (!enabled) {
            claimedPositions.clear();
        }
        return true;
    }

    public boolean isDoorRepairEnabled() {
        return doorRepairEnabled;
    }

    public boolean setDoorRepairEnabled(boolean enabled) {
        if (doorRepairEnabled == enabled) {
            return false;
        }
        doorRepairEnabled = enabled;
        if (!enabled) {
            doorRegistry.clear();
            missingDoorRegistry.clear();
            if (fullScanState != null) {
                fullScanState.doors.clear();
            }
        }
        return true;
    }

    public boolean isAbandonedVillage() {
        return abandonedVillage;
    }

    public void setAbandonedVillage(boolean abandonedVillage) {
        this.abandonedVillage = abandonedVillage;
    }

    public BlockPos getBellPosition() {
        return bellPosition;
    }

    public void setBellPosition(BlockPos bellPosition) {
        this.bellPosition = bellPosition.immutable();
    }

    public AABB getBoundingBox() {
        return boundingBox;
    }

    /**
     * Records the generated structure extent that initial adaptive scans must cross
     * before empty-ring pruning is allowed in each direction.
     *
     * @return true when the stored anchor bounds changed
     */
    public boolean setInitialScanAnchorBounds(AABB bounds) {
        Objects.requireNonNull(bounds, "bounds");
        AABB normalized = new AABB(
                Math.min(bounds.minX, bounds.maxX),
                Math.min(bounds.minY, bounds.maxY),
                Math.min(bounds.minZ, bounds.maxZ),
                Math.max(bounds.minX, bounds.maxX),
                Math.max(bounds.minY, bounds.maxY),
                Math.max(bounds.minZ, bounds.maxZ)
        );
        if (sameBounds(initialScanAnchorBounds, normalized)) {
            return false;
        }
        initialScanAnchorBounds = normalized;
        return true;
    }

    private static boolean sameBounds(AABB first, AABB second) {
        return first != null
                && Double.compare(first.minX, second.minX) == 0
                && Double.compare(first.minY, second.minY) == 0
                && Double.compare(first.minZ, second.minZ) == 0
                && Double.compare(first.maxX, second.maxX) == 0
                && Double.compare(first.maxY, second.maxY) == 0
                && Double.compare(first.maxZ, second.maxZ) == 0;
    }

    /**
     * Updates the bounding box. If the cache is initialized and the new box is
     * larger, only the delta volume (new minus old) is scanned for beds and
     * job sites. Positions that fall outside the new box are pruned.
     */
    public void setBoundingBox(AABB newBox, ServerLevel level) {
        AABB oldBox = this.boundingBox;
        this.boundingBox = newBox;
        VillageHostility.clearLookupCache();
        VillageOpinionCache.invalidateVillage(villageId);

        if (!cacheInitialized) {
            return;
        }

        // Prune positions no longer inside the new box
        cachedBedPositions.removeIf(pos -> !containsPos(newBox, pos));
        cachedJobSitePositions.keySet().removeIf(pos -> !containsPos(newBox, pos));
        farmlandRegistry.removeIf(pos -> !containsPos(newBox, pos));
        doorRegistry.removeIf(pos -> !containsPos(newBox, pos));
        missingDoorRegistry.removeIf(pos -> !containsPos(newBox, pos));
        repairQueue.removeIf(pos -> !containsPos(newBox, pos));
        claimedPositions.removeIf(pos -> !containsPos(newBox, pos));

        // Scan only the delta volume (regions in newBox but not in oldBox)
        scanDeltaArea(level, oldBox, newBox);
    }

    /** Legacy setter without level: does not trigger delta scan. */
    public void setBoundingBox(AABB boundingBox) {
        this.boundingBox = boundingBox;
        VillageHostility.clearLookupCache();
        VillageOpinionCache.invalidateVillage(villageId);
    }

    public Map<UUID, VillagerPOIRecord> getMembers() {
        return members;
    }

    public Map<UUID, VillagerPOIRecord> getMembersSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(members));
    }

    public boolean isCacheInitialized() {
        return cacheInitialized;
    }

    // Member management

    public void addMember(VillagerPOIRecord record) {
        members.put(record.getVillagerUUID(), record);
    }

    public void removeMember(UUID villagerUUID) {
        members.remove(villagerUUID);
    }

    public boolean hasMember(UUID villagerUUID) {
        return members.containsKey(villagerUUID);
    }

    /** Returns the persistent village-level opinion modifier for a player. */
    public int getOpinionModifier(UUID playerId) {
        return opinionModifiers.getOrDefault(playerId, 0);
    }

    /**
     * Applies a village-level opinion change to a player. The modifier is kept
     * separately from vanilla villager gossip so the ledger can explain the
     * average villager opinion plus concrete village actions.
     */
    public int adjustOpinionModifier(UUID playerId, int delta) {
        long requested = (long) opinionModifiers.getOrDefault(playerId, 0) + delta;
        int updated = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, requested));
        if (updated == 0) {
            opinionModifiers.remove(playerId);
        } else {
            opinionModifiers.put(playerId, updated);
        }
        VillageOpinionCache.invalidateVillage(villageId);
        return updated;
    }

    private static <T> DataResult<Boolean> readOptionalBoolean(DynamicOps<T> ops, MapLike<T> map,
                                                               String key, boolean fallback) {
        T encoded = map.get(ops.createString(key));
        if (encoded == null) {
            return DataResult.success(fallback);
        }
        return Codec.BOOL.parse(ops, encoded);
    }

    private static <T> DataResult<Long> readOptionalLong(DynamicOps<T> ops, MapLike<T> map,
                                                         String key, long fallback) {
        T encoded = map.get(ops.createString(key));
        if (encoded == null) {
            return DataResult.success(fallback);
        }
        return Codec.LONG.validate(value -> value >= 0L
                ? DataResult.success(value)
                : DataResult.error(() -> key + " cannot be negative"))
                .parse(ops, encoded);
    }

    private static <T> DataResult<Optional<UUID>> readOptionalUuid(DynamicOps<T> ops,
                                                                    MapLike<T> map,
                                                                    String key) {
        T encoded = map.get(ops.createString(key));
        if (encoded == null) {
            return DataResult.success(Optional.empty());
        }
        return UUIDUtil.CODEC.parse(ops, encoded).map(value -> Optional.of(value));
    }

    private static <T> DataResult<Optional<BlockPos>> readOptionalBlockPos(DynamicOps<T> ops,
                                                                            MapLike<T> map,
                                                                            String key) {
        T encoded = map.get(ops.createString(key));
        if (encoded == null) {
            return DataResult.success(Optional.empty());
        }
        return BlockPos.CODEC.parse(ops, encoded).map(value -> Optional.of(value));
    }

    /** Returns whether this player is the village's appointed governor. */
    public boolean isGovernor(UUID playerId) {
        return playerId != null && playerId.equals(governorId);
    }

    /** Returns whether this player has registered as a governor candidate. */
    public boolean isGovernorCandidate(UUID playerId) {
        return playerId != null && playerId.equals(governorCandidateId);
    }

    @Nullable
    public UUID getGovernorCandidateId() {
        return governorCandidateId;
    }

    /** Appoints a governor and removes that player from the candidate pool. */
    public boolean setGovernor(@Nullable UUID playerId) {
        if (Objects.equals(governorId, playerId)) {
            return false;
        }
        governorId = playerId;
        if (playerId != null) {
            if (playerId.equals(governorCandidateId)) {
                governorCandidateId = null;
                governorCandidateAttackPlayerId = null;
                governorCandidateAttackBankPos = null;
                governorCandidateAttackMayorId = null;
                governorCandidateAttackGraceUntil = 0L;
            }
        }
        return true;
    }

    /** Registers a player as a candidate and starts the bank's attack grace period. */
    public boolean becomeGovernorCandidate(UUID playerId, int opinion, long gameTime) {
        if (playerId == null || governorCandidateId != null
                || !VillageRelationship.canBecomeGovernorCandidate(
                opinion, Config.governorCandidateOpinionThreshold)
                || isGovernor(playerId)) {
            return false;
        }
        if (gameTime < 0L) {
            throw new IllegalArgumentException("Game time cannot be negative");
        }
        governorCandidateId = playerId;
        governorCandidateAttackPlayerId = playerId;
        governorCandidateAttackBankPos = null;
        governorCandidateAttackMayorId = null;
        governorCandidateAttackGraceUntil = gameTime > Long.MAX_VALUE
                - GOVERNOR_CANDIDATE_ATTACK_GRACE_TICKS
                ? Long.MAX_VALUE
                : gameTime + GOVERNOR_CANDIDATE_ATTACK_GRACE_TICKS;
        return true;
    }

    /** Removes the current candidate, preserving the one-candidate invariant. */
    public boolean clearGovernorCandidate() {
        if (governorCandidateId == null) {
            return false;
        }
        governorCandidateId = null;
        governorCandidateAttackPlayerId = null;
        governorCandidateAttackBankPos = null;
        governorCandidateAttackMayorId = null;
        governorCandidateAttackGraceUntil = 0L;
        return true;
    }

    /** Binds the grace period to the bank and Mayor involved in this election. */
    public boolean bindGovernorCandidateAttackGrace(BlockPos bankPos, UUID mayorId) {
        if (governorCandidateId == null || bankPos == null || mayorId == null) {
            return false;
        }
        governorCandidateAttackPlayerId = governorCandidateId;
        governorCandidateAttackBankPos = bankPos.immutable();
        governorCandidateAttackMayorId = mayorId;
        return true;
    }

    /** Returns whether this exact bank, candidate, and Mayor have passed grace. */
    public boolean isGovernorCandidateAttackGraceElapsed(BlockPos bankPos, UUID playerId,
                                                          UUID mayorId, long gameTime) {
        if (!isGovernorCandidate(playerId)
                || !playerId.equals(governorCandidateAttackPlayerId)
                || bankPos == null
                || !bankPos.equals(governorCandidateAttackBankPos)
                || mayorId == null
                || !mayorId.equals(governorCandidateAttackMayorId)) {
            return false;
        }
        return governorCandidateAttackGraceUntil == 0L
                || gameTime >= governorCandidateAttackGraceUntil;
    }

    /** Ends grace when the current candidate acts against this exact bank and Mayor. */
    public boolean endGovernorCandidateAttackGrace(BlockPos bankPos, UUID playerId, UUID mayorId) {
        if (governorCandidateAttackGraceUntil == 0L
                || !isGovernorCandidate(playerId)
                || !playerId.equals(governorCandidateAttackPlayerId)
                || bankPos == null
                || !bankPos.equals(governorCandidateAttackBankPos)
                || mayorId == null
                || !mayorId.equals(governorCandidateAttackMayorId)) {
            return false;
        }
        governorCandidateAttackGraceUntil = 0L;
        return true;
    }

    /** Resolves the viewer's relationship using the current server-authoritative opinion. */
    public VillageRelationship getPlayerRelationship(ServerLevel level, Player player) {
        int opinion = getVillageOpinion(level, player);
        return VillageRelationship.resolve(opinion, Config.governorHostileOpinionThreshold,
                Config.governorCandidateOpinionThreshold, isGovernor(player.getUUID()),
                isGovernorCandidate(player.getUUID()));
    }

    /**
     * Calculates "Village Opinion of You" as the rounded average of the
     * current villagers' vanilla reputations plus this player's persistent
     * village-action modifier.
     */
    public int getVillageOpinion(ServerLevel level, Player player) {
        return VillageOpinionCache.get(level, this, player);
    }

    /** Computes live opinion without entering the per-tick cache. */
    int calculateVillageOpinion(ServerLevel level, Player player) {
        List<Villager> villagers = level.getEntitiesOfClass(
                Villager.class, boundingBox,
                villager -> villager.isAlive() && members.containsKey(villager.getUUID()));
        if (villagers.isEmpty()) {
            return getOpinionModifier(player.getUUID());
        }

        int total = 0;
        for (Villager villager : villagers) {
            total += villager.getPlayerReputation(player);
        }
        int average = Math.round(total / (float) villagers.size());
        return average + getOpinionModifier(player.getUUID());
    }

    // Block-to-profession mapping

    private static final Map<Class<? extends Block>, String> WORKSTATION_BLOCKS = new IdentityHashMap<>();
    static {
        WORKSTATION_BLOCKS.put(BlastFurnaceBlock.class, "Armorer");
        WORKSTATION_BLOCKS.put(BrewingStandBlock.class, "Cleric");
        WORKSTATION_BLOCKS.put(CartographyTableBlock.class, "Cartographer");
        WORKSTATION_BLOCKS.put(ComposterBlock.class, "Farmer");
        WORKSTATION_BLOCKS.put(BarrelBlock.class, "Fisherman");
        WORKSTATION_BLOCKS.put(FletchingTableBlock.class, "Fletcher");
        WORKSTATION_BLOCKS.put(LecternBlock.class, "Librarian");
        WORKSTATION_BLOCKS.put(StonecutterBlock.class, "Mason");
        WORKSTATION_BLOCKS.put(LoomBlock.class, "Shepherd");
        WORKSTATION_BLOCKS.put(SmithingTableBlock.class, "Toolsmith");
        WORKSTATION_BLOCKS.put(SmokerBlock.class, "Butcher");
        WORKSTATION_BLOCKS.put(GrindstoneBlock.class, "Weaponsmith");
        WORKSTATION_BLOCKS.put(CauldronBlock.class, "Leatherworker");
        WORKSTATION_BLOCKS.put(LayeredCauldronBlock.class, "Leatherworker");
        // Village infrastructure blocks tracked as special POIs
        WORKSTATION_BLOCKS.put(BankBlock.class, "Bank");
        WORKSTATION_BLOCKS.put(VillageManagerBlock.class, "Village Ledger");
    }

    /** Returns the profession name for a workstation block class, or null. */
    public static String getWorkstationType(Block block) {
        return WORKSTATION_BLOCKS.get(block.getClass());
    }

    /** Returns true if the block is a bed head part. */
    public static boolean isBedHead(BlockState state) {
        return state.getBlock() instanceof BedBlock
                && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    /** Returns true only for the lower half, so a two-block door is counted once. */
    public static boolean isDoorBase(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    /** Converts either half of a door to its canonical lower-half position. */
    public static BlockPos doorBasePos(BlockPos pos, BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                ? pos.below()
                : pos;
    }

    /** Palette-level filter used to avoid visiting sections that cannot affect a full scan. */
    private static boolean isFullScanCandidate(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BedBlock
                || WORKSTATION_BLOCKS.containsKey(block.getClass())
                || block instanceof FarmBlock
                || block instanceof DoorBlock
                || state.is(Blocks.BELL)
                || state.is(Blocks.DIRT_PATH)
                || block instanceof VillageManagerBlock
                || block instanceof BankBlock;
    }

    // Full scan (initial or manual)

    /**
     * Performs a complete scan of the bounding box, rebuilding the bed,
     * job-site, farmland, and door caches from scratch. Called once on first access
     * and on manual re-scan requests.
     */
    public void fullScan(ServerLevel level) {
        beginFullScan();
        while (!processFullScan(level, Integer.MAX_VALUE)) {
            // The synchronous compatibility entry point is only for tests and controlled setup.
        }
        completeFullScan();
    }

    /** Starts a full scan without mutating the published caches until it completes. */
    public void beginFullScan() {
        beginFullScan(!cacheInitialized);
    }

    /**
     * Starts a scan with an explicit traversal mode. Adaptive traversal is intended only
     * for rebuilding an uninitialized cache; manual expanded-bounds rescans stay exhaustive.
     */
    public void beginFullScan(boolean adaptiveInitialScan) {
        Set<BlockPos> knownDoorPositions = new HashSet<>(doorRegistry);
        knownDoorPositions.addAll(missingDoorRegistry);
        FullScanState state = new FullScanState(
                boundingBox,
                bellPosition,
                initialScanAnchorBounds,
                farmlandRegistry,
                knownDoorPositions,
                adaptiveInitialScan
        );
        if (adaptiveInitialScan) {
            // Generated farms are registered as they are placed. Keep those authoritative
            // anchors even when their chunks are temporarily unavailable during this scan.
            state.farmland.addAll(farmlandRegistry);
        }
        fullScanState = state;
        fullScanCompletionPending = true;
    }

    /**
     * Processes at most {@code blockBudget} scan work units. A unit is either one inspected
     * block or one skipped vertical section slice whose palette has no relevant block state.
     *
     * @return true when the scan has completed and its results were published
     */
    public boolean processFullScan(ServerLevel level, int blockBudget) {
        return processFullScan(level, blockBudget, null);
    }

    /**
     * Processes a full-scan slice, optionally allowing adaptive initial scans to wait for a
     * bounded asynchronous chunk request. Manual and initialized-cache scans never use the pool.
     */
    public boolean processFullScan(
            ServerLevel level,
            int blockBudget,
            @Nullable InitialVillageScanChunkLoadPool chunkLoadPool
    ) {
        if (fullScanState == null) {
            beginFullScan();
        }

        FullScanState state = fullScanState;
        state.beginSlice();
        prefetchAdaptiveChunks(chunkLoadPool, state);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int budgetUnits = 0;
        while (budgetUnits < blockBudget) {
            pos.set(state.x, state.y, state.z);
            if (!level.hasChunk(state.chunkX, state.chunkZ)) {
                if (chunkLoadPool != null && state.adaptivePlan != null) {
                    InitialVillageScanChunkLoadPool.Availability availability = chunkLoadPool.ensureAvailable(
                            villageId,
                            state.chunkX,
                            state.chunkZ
                    );
                    if (availability == InitialVillageScanChunkLoadPool.Availability.WAITING) {
                        return false;
                    }
                    if (availability == InitialVillageScanChunkLoadPool.Availability.BATCH_LIMIT) {
                        if (chunkLoadPool.beginNextBatch(villageId)) {
                            prefetchAdaptiveChunks(chunkLoadPool, state);
                        }
                        return false;
                    }
                    if (availability == InitialVillageScanChunkLoadPool.Availability.AVAILABLE
                            && level.hasChunk(state.chunkX, state.chunkZ)) {
                        continue;
                    }

                    // Do not publish an adaptive initial scan that skipped an unloaded
                    // chunk. Its result would be incomplete, and shrinkToFit() would
                    // permanently discard the part of the village in that chunk.
                    state.encounteredUnavailableChunk = true;
                }

                releaseTemporaryChunk(chunkLoadPool, state.chunkX, state.chunkZ);
                if (state.skipUnloadedChunkColumn()) {
                    if (chunkLoadPool != null
                            && state.adaptivePlan != null
                            && state.encounteredUnavailableChunk) {
                        // Start a fresh pass after the pool has had a chance to load
                        // another bounded set of chunks. Keeping the cache uninitialized
                        // prevents an incomplete pass from becoming authoritative.
                        chunkLoadPool.finishScan(villageId);
                        beginFullScan(true);
                        return false;
                    }
                    return publishFullScan(level, state);
                }
                continue;
            }

            LevelChunk chunk = level.getChunk(state.chunkX, state.chunkZ);
            if (!state.currentSectionMayContainCandidates(chunk)) {
                budgetUnits++;
                int scannedChunkX = state.chunkX;
                int scannedChunkZ = state.chunkZ;
                if (state.skipCurrentSectionColumn()) {
                    releaseTemporaryChunk(chunkLoadPool, scannedChunkX, scannedChunkZ);
                    return publishFullScan(level, state);
                }
                if (state.chunkX != scannedChunkX || state.chunkZ != scannedChunkZ) {
                    releaseTemporaryChunk(chunkLoadPool, scannedChunkX, scannedChunkZ);
                    prefetchAdaptiveChunks(chunkLoadPool, state);
                }
                continue;
            }

            BlockState blockState = chunk.getBlockState(pos);
            boolean interesting = false;
            if (isBedHead(blockState)) {
                state.beds.add(pos.immutable());
                interesting = true;
            }
            String jobType = WORKSTATION_BLOCKS.get(blockState.getBlock().getClass());
            if (jobType != null) {
                state.jobSites.put(pos.immutable(), jobType);
                interesting = true;
            }
            if (blockState.getBlock() instanceof FarmBlock) {
                state.farmland.add(pos.immutable());
                interesting = true;
            }
            if (doorRepairEnabled && isDoorBase(blockState)) {
                state.doors.add(pos.immutable());
                interesting = true;
            }
            Block block = blockState.getBlock();
            if (blockState.is(Blocks.BELL)
                    || blockState.is(Blocks.DIRT_PATH)
                    || block instanceof VillageManagerBlock
                    || block instanceof BankBlock) {
                interesting = true;
            }
            if (interesting) {
                state.markCurrentChunkInteresting();
            }
            budgetUnits++;
            int scannedChunkX = state.chunkX;
            int scannedChunkZ = state.chunkZ;
            if (state.advance()) {
                releaseTemporaryChunk(chunkLoadPool, scannedChunkX, scannedChunkZ);
                return publishFullScan(level, state);
            }
            if (state.chunkX != scannedChunkX || state.chunkZ != scannedChunkZ) {
                releaseTemporaryChunk(chunkLoadPool, scannedChunkX, scannedChunkZ);
                prefetchAdaptiveChunks(chunkLoadPool, state);
            }
        }
        return false;
    }

    private void prefetchAdaptiveChunks(
            @Nullable InitialVillageScanChunkLoadPool chunkLoadPool,
            FullScanState state
    ) {
        if (chunkLoadPool != null && state.adaptivePlan != null) {
            chunkLoadPool.prefetch(villageId, state.upcomingChunks(INITIAL_SCAN_PREFETCH_WINDOW));
        }
    }

    private void releaseTemporaryChunk(
            @Nullable InitialVillageScanChunkLoadPool chunkLoadPool,
            int chunkX,
            int chunkZ
    ) {
        if (chunkLoadPool != null) {
            chunkLoadPool.release(villageId, chunkX, chunkZ);
        }
    }

    private boolean publishFullScan(ServerLevel level, FullScanState state) {
        cachedBedPositions.clear();
        cachedBedPositions.addAll(state.beds);
        cachedJobSitePositions.clear();
        cachedJobSitePositions.putAll(state.jobSites);
        farmlandRegistry.clear();
        farmlandRegistry.addAll(state.farmland);
        Set<BlockPos> previouslyKnownDoors = new HashSet<>(doorRegistry);
        doorRegistry.clear();
        doorRegistry.addAll(state.doors);
        missingDoorRegistry.addAll(previouslyKnownDoors);
        missingDoorRegistry.removeAll(state.doors);
        missingDoorRegistry.removeIf(pos -> !containsPos(boundingBox, pos));
        reconcileRepairQueue(level);
        cacheInitialized = true;
        fullScanState = null;
        shrinkToFit();
        return true;
    }

    public boolean isFullScanInProgress() {
        return fullScanState != null || fullScanCompletionPending;
    }

    /** Marks the full scan complete after its budgeted villager refresh has finished. */
    public void completeFullScan() {
        fullScanCompletionPending = false;
    }

    // Verify (cheap periodic check)

    /**
     * Verifies that all cached positions still contain the expected block.
     * Removes any that have been destroyed or replaced. This is O(N) where
     * N is the number of cached beds + job sites (typically 10–30), not the
     * bounding box volume.
     * @return true when this persistent record was changed.
     */
    public boolean verify(ServerLevel level) {
        if (!cacheInitialized) {
            return false;
        }

        boolean bedsChanged = cachedBedPositions.removeIf(pos -> {
            BlockState state = level.getBlockState(pos);
            return !isBedHead(state);
        });

        boolean jobSitesChanged = cachedJobSitePositions.entrySet().removeIf(entry -> {
            BlockState state = level.getBlockState(entry.getKey());
            String type = WORKSTATION_BLOCKS.get(state.getBlock().getClass());
            return type == null;
        });
        boolean doorsChanged = false;
        if (doorRepairEnabled) {
            Iterator<BlockPos> doors = doorRegistry.iterator();
            while (doors.hasNext()) {
                BlockPos pos = doors.next();
                if (!isDoorBase(level.getBlockState(pos))) {
                    doors.remove();
                    missingDoorRegistry.add(pos.immutable());
                    doorsChanged = true;
                }
            }
        }
        return bedsChanged || jobSitesChanged || doorsChanged;
    }

    // Event-driven cache updates

    /**
     * Called when a player places a block inside this village's bounding box.
     * If the block is a bed head or workstation, it is added to the cache.
     */
    public void onBlockPlaced(BlockPos pos, BlockState state) {
        if (!cacheInitialized && fullScanState == null) {
            return;
        }
        if (isBedHead(state)) {
            if (cacheInitialized) cachedBedPositions.add(pos.immutable());
            if (fullScanState != null) fullScanState.beds.add(pos.immutable());
        }
        String jobType = WORKSTATION_BLOCKS.get(state.getBlock().getClass());
        if (jobType != null) {
            if (cacheInitialized) cachedJobSitePositions.put(pos.immutable(), jobType);
            if (fullScanState != null) fullScanState.jobSites.put(pos.immutable(), jobType);
        }
        if (doorRepairEnabled && state.getBlock() instanceof DoorBlock) {
            addDoor(doorBasePos(pos, state));
            missingDoorRegistry.remove(doorBasePos(pos, state));
        }
    }

    /**
     * Called when a player breaks a block inside this village's bounding box.
     * Removes the position from whichever cache it belongs to.
     */
    public void onBlockRemoved(BlockPos pos) {
        if (!cacheInitialized && fullScanState == null) {
            return;
        }
        if (cacheInitialized) {
            cachedBedPositions.remove(pos);
            cachedJobSitePositions.remove(pos);
            markDoorMissing(pos);
            markDoorMissing(pos.below());
        }
        if (fullScanState != null) {
            fullScanState.beds.remove(pos);
            fullScanState.jobSites.remove(pos);
            fullScanState.doors.remove(pos);
            fullScanState.doors.remove(pos.below());
        }
    }

    // Farmland registry methods

    /** Returns an unmodifiable view of the farmland registry. */
    public Set<BlockPos> getFarmlandRegistry() {
        return Collections.unmodifiableSet(farmlandRegistry);
    }

    /** Returns an unmodifiable view of canonical lower-half door positions. */
    public Set<BlockPos> getDoorRegistry() {
        return Collections.unmodifiableSet(doorRegistry);
    }

    /** Returns previously tracked door positions that are currently missing. */
    public Set<BlockPos> getMissingDoorRegistry() {
        return Collections.unmodifiableSet(missingDoorRegistry);
    }

    /** Returns an unmodifiable view of the repair queue. */
    public Set<BlockPos> getRepairQueue() {
        return Collections.unmodifiableSet(repairQueue);
    }

    /** Returns an unmodifiable view of claimed positions. */
    public Set<BlockPos> getClaimedPositions() {
        return Collections.unmodifiableSet(claimedPositions);
    }

    /**
     * Reconciles the repair queue after a full scan. Positions still in the
     * farmland registry (already farmland again) are removed from the queue.
     * Positions with tillable blocks (dirt/grass) are kept and re-added to
     * the registry. All other positions are stale and removed.
     */
    private void reconcileRepairQueue(ServerLevel level) {
        Iterator<BlockPos> it = repairQueue.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (farmlandRegistry.contains(pos)) {
                // Already farmland again: no longer needs repair
                it.remove();
            } else {
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)) {
                    // Tillable: keep in repair queue and track in registry
                    farmlandRegistry.add(pos.immutable());
                } else {
                    // Non-tillable (stone, air, etc.): truly stale
                    it.remove();
                }
            }
        }
        claimedPositions.retainAll(repairQueue);
    }

    /** Adds a position to the farmland registry, returning whether persistent state changed. */
    public boolean addFarmland(BlockPos pos) {
        boolean added = farmlandRegistry.add(pos.immutable());
        if (fullScanState != null) fullScanState.farmland.add(pos.immutable());
        return added;
    }

    /** Adds a canonical door position, returning whether persistent state changed. */
    public boolean addDoor(BlockPos pos) {
        if (!doorRepairEnabled) {
            return false;
        }
        boolean added = doorRegistry.add(pos.immutable());
        missingDoorRegistry.remove(pos);
        if (fullScanState != null) fullScanState.doors.add(pos.immutable());
        return added;
    }

    /** Removes a canonical door position from the registry. */
    public void removeDoor(BlockPos pos) {
        doorRegistry.remove(pos);
        if (fullScanState != null) fullScanState.doors.remove(pos);
    }

    /** Records a tracked door as missing after its block was destroyed. */
    public boolean markDoorMissing(BlockPos pos) {
        if (!doorRepairEnabled) {
            return false;
        }
        boolean wasTracked = doorRegistry.contains(pos);
        if (doorRegistry.remove(pos)) {
            missingDoorRegistry.add(pos.immutable());
        }
        if (fullScanState != null) {
            fullScanState.doors.remove(pos);
        }
        return wasTracked;
    }

    /** Marks a missing door as repaired and removes its repair target. */
    public boolean markDoorRepaired(BlockPos pos) {
        if (!doorRepairEnabled) {
            return false;
        }
        boolean wasMissing = missingDoorRegistry.remove(pos);
        boolean wasAdded = doorRegistry.add(pos.immutable());
        if (fullScanState != null) {
            fullScanState.doors.add(pos.immutable());
        }
        return wasMissing || wasAdded;
    }

    /** Clears remembered missing-door targets when a governor explicitly resets the door cache. */
    public boolean clearMissingDoors() {
        boolean changed = !missingDoorRegistry.isEmpty();
        missingDoorRegistry.clear();
        return changed;
    }

    /** Removes a position from the farmland registry. */
    public void removeFarmland(BlockPos pos) {
        farmlandRegistry.remove(pos);
        if (fullScanState != null) fullScanState.farmland.remove(pos);
        repairQueue.remove(pos);
        claimedPositions.remove(pos);
    }

    /** Adds a position to the repair queue if it is in the farmland registry. */
    public boolean addToRepairQueue(BlockPos pos) {
        return farmlandRegistry.contains(pos) && repairQueue.add(pos.immutable());
    }

    /** Removes a position from the repair queue. */
    public void removeFromRepairQueue(BlockPos pos) {
        repairQueue.remove(pos);
    }

    /**
     * Claims a repair queue position for a farmer. Returns true if the position
     * was unclaimed and in the repair queue.
     */
    public boolean claimPosition(BlockPos pos) {
        if (repairQueue.contains(pos) && !claimedPositions.contains(pos)) {
            claimedPositions.add(pos.immutable());
            return true;
        }
        return false;
    }

    /** Unclaims a position so another farmer can take it. */
    public void unclaimPosition(BlockPos pos) {
        claimedPositions.remove(pos);
    }

    /** Clears all claimed positions. Called on server load. */
    public void clearClaimed() {
        claimedPositions.clear();
    }

    /**
     * Returns the nearest unclaimed repair queue entry within the given range
     * of the specified position, or null if none found.
     */
    public BlockPos getNearestUnclaimedRepair(BlockPos origin, double maxRange) {
        double maxRangeSq = maxRange * maxRange;
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : repairQueue) {
            if (claimedPositions.contains(pos)) {
                continue;
            }
            double distSq = origin.distSqr(pos);
            if (distSq <= maxRangeSq && distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = pos;
            }
        }
        return nearest;
    }

    // Data retrieval (from cache)

    /** Returns a copy of all cached bed head positions. */
    public List<BlockPos> getBedPositions() {
        return new ArrayList<>(cachedBedPositions);
    }

    /**
     * Returns the current bed count as {@code [totalBeds, availableBeds]}
     * from the cache. The "available" count is based on BedBlock.OCCUPIED
     * state at the moment of the call.
     */
    public int[] countBeds(ServerLevel level) {
        if (!cacheInitialized) {
            return new int[]{0, 0};
        }
        int total = cachedBedPositions.size();
        int available = 0;
        for (BlockPos pos : cachedBedPositions) {
            BlockState state = level.getBlockState(pos);
            boolean occupied = state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
            if (!occupied) {
                available++;
            }
        }
        return new int[]{total, available};
    }

    /**
     * Returns job-site entries from the cache, each marked as claimed or
     * unclaimed by cross-referencing members' {@code jobSitePos}.
     */
    public List<JobSiteEntry> getJobSites() {
        Set<BlockPos> claimedPositions = new HashSet<>();
        for (VillagerPOIRecord member : members.values()) {
            if (member.getJobSitePos() != null) {
                claimedPositions.add(member.getJobSitePos());
            }
        }

        List<JobSiteEntry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, String> entry : cachedJobSitePositions.entrySet()) {
            boolean claimed = claimedPositions.contains(entry.getKey());
            entries.add(new JobSiteEntry(entry.getKey(), entry.getValue(), claimed));
        }
        return entries;
    }

    // Dynamic bounding box

    /**
     * Shrinks the bounding box to tightly fit all discovered village blocks
     * (beds, job sites, farmland, repair queue) and the bell position, plus
     * {@link #BOUNDARY_MARGIN} blocks on each side. If no blocks were found
     * the box is left unchanged.
     */
    private void shrinkToFit() {
        // Collect all tracked positions
        List<BlockPos> allPositions = new ArrayList<>();
        allPositions.addAll(cachedBedPositions);
        allPositions.addAll(cachedJobSitePositions.keySet());
        allPositions.addAll(farmlandRegistry);
        allPositions.addAll(doorRegistry);
        allPositions.addAll(missingDoorRegistry);
        allPositions.addAll(repairQueue);

        if (allPositions.isEmpty()) {
            // Nothing found: keep current box (village might just have a bell)
            return;
        }

        // Start min/max from the bell so it is always included
        int minX = bellPosition.getX();
        int minY = bellPosition.getY();
        int minZ = bellPosition.getZ();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;

        for (BlockPos pos : allPositions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        AABB tightBox = new AABB(
                minX - BOUNDARY_MARGIN, minY - BOUNDARY_MARGIN, minZ - BOUNDARY_MARGIN,
                maxX + BOUNDARY_MARGIN, maxY + BOUNDARY_MARGIN, maxZ + BOUNDARY_MARGIN
        );

        // Only shrink: never grow beyond the original scan area
        AABB shrunk = new AABB(
                Math.max(tightBox.minX, boundingBox.minX),
                Math.max(tightBox.minY, boundingBox.minY),
                Math.max(tightBox.minZ, boundingBox.minZ),
                Math.min(tightBox.maxX, boundingBox.maxX),
                Math.min(tightBox.maxY, boundingBox.maxY),
                Math.min(tightBox.maxZ, boundingBox.maxZ)
        );

        // Only update if the box actually shrank meaningfully
        if (shrunk.minX > boundingBox.minX || shrunk.minY > boundingBox.minY || shrunk.minZ > boundingBox.minZ
                || shrunk.maxX < boundingBox.maxX || shrunk.maxY < boundingBox.maxY || shrunk.maxZ < boundingBox.maxZ) {

            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Village {} bounding box shrunk from [({}, {}, {}) to ({}, {}, {})] "
                            + "to [({}, {}, {}) to ({}, {}, {})]",
                    villageId.toString().substring(0, 8),
                    (int) boundingBox.minX, (int) boundingBox.minY, (int) boundingBox.minZ,
                    (int) boundingBox.maxX, (int) boundingBox.maxY, (int) boundingBox.maxZ,
                    (int) shrunk.minX, (int) shrunk.minY, (int) shrunk.minZ,
                    (int) shrunk.maxX, (int) shrunk.maxY, (int) shrunk.maxZ
            );

            this.boundingBox = shrunk;
        }
    }

    // Internal scanning helpers

    /** Scans the given AABB for beds, job sites, farmland, and doors, adding to the caches. */
    private void scanArea(ServerLevel level, AABB area) {
        int minX = (int) Math.floor(area.minX);
        int minY = (int) Math.floor(area.minY);
        int minZ = (int) Math.floor(area.minZ);
        int maxX = (int) Math.floor(area.maxX);
        int maxY = (int) Math.floor(area.maxY);
        int maxZ = (int) Math.floor(area.maxZ);

        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);

            if (isBedHead(state)) {
                cachedBedPositions.add(pos.immutable());
            }

            String jobType = WORKSTATION_BLOCKS.get(state.getBlock().getClass());
            if (jobType != null) {
                cachedJobSitePositions.put(pos.immutable(), jobType);
            }

            if (state.getBlock() instanceof FarmBlock) {
                farmlandRegistry.add(pos.immutable());
            }
            if (doorRepairEnabled && isDoorBase(state)) {
                doorRegistry.add(pos.immutable());
            }
        }
    }

    /**
     * Scans only the volume that is in {@code newBox} but was not in
     * {@code oldBox}. This is the delta from a bounding-box expansion.
     */
    private void scanDeltaArea(ServerLevel level, AABB oldBox, AABB newBox) {
        // Scan the expanded box and skip positions inside the old box.
        // For most expansions (one side grows) this skips the vast majority of blocks.
        int minX = (int) Math.floor(newBox.minX);
        int minY = (int) Math.floor(newBox.minY);
        int minZ = (int) Math.floor(newBox.minZ);
        int maxX = (int) Math.floor(newBox.maxX);
        int maxY = (int) Math.floor(newBox.maxY);
        int maxZ = (int) Math.floor(newBox.maxZ);

        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            // Skip if this position was inside the old box (already cached)
            if (containsPos(oldBox, pos)) {
                continue;
            }
            if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);

            if (isBedHead(state)) {
                cachedBedPositions.add(pos.immutable());
            }

            String jobType = WORKSTATION_BLOCKS.get(state.getBlock().getClass());
            if (jobType != null) {
                cachedJobSitePositions.put(pos.immutable(), jobType);
            }

            if (state.getBlock() instanceof FarmBlock) {
                farmlandRegistry.add(pos.immutable());
            }
            if (doorRepairEnabled && isDoorBase(state)) {
                doorRegistry.add(pos.immutable());
            }
        }
    }

    private static boolean containsPos(AABB box, BlockPos pos) {
        return pos.getX() >= box.minX && pos.getX() <= box.maxX
                && pos.getY() >= box.minY && pos.getY() <= box.maxY
                && pos.getZ() >= box.minZ && pos.getZ() <= box.maxZ;
    }

    // NBT Serialization

    public CompoundTag save(CompoundTag tag) {
        DataResult<net.minecraft.nbt.Tag> encoded = CODEC.encodeStart(NbtOps.INSTANCE, this);
        return encoded.resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not encode village record: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .map(encodedTag -> {
                    tag.merge(encodedTag);
                    return tag;
                })
                .orElse(tag);
    }

    public static VillageRecord load(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Could not decode village record: {}", message))
                .orElseThrow(() -> new IllegalArgumentException("Invalid village record"));
    }
}
