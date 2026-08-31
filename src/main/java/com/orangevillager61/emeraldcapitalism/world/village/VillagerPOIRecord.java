package com.orangevillager61.emeraldcapitalism.world.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain data class tracking a single villager's point-of-interest information
 * within a village: their bed, job site, health, family, and activity status.
 */
public class VillagerPOIRecord {

    public enum Status {
        ACTIVE,
        DEPARTED;

        public static Status fromString(String name) {
            try {
                return Status.valueOf(name);
            } catch (IllegalArgumentException e) {
                return ACTIVE;
            }
        }
    }

    private static final int MAX_STATUS_NAME_LENGTH = 8;
    private static final float MAX_PERSISTED_HEALTH = 1_000.0F;
    private static final int MAX_PERSISTED_DEPARTURE_COUNTER = 1_000_000;
    private static final long MAX_PERSISTED_GAME_TIME = 1_000_000_000_000L;
    private static final Codec<Status> STATUS_CODEC = boundedStringCodec(
            MAX_STATUS_NAME_LENGTH, "Villager status").xmap(Status::fromString, Status::name);
    private static final Codec<String> DISPLAY_NAME_CODEC = boundedStringCodec(
            ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH, "Villager display name");
    private static final Codec<String> PROFESSION_CODEC = boundedStringCodec(
            ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH, "Villager profession");
    private static final Codec<Float> HEALTH_CODEC = Codec.FLOAT.validate(value ->
            Float.isFinite(value) && value >= 0.0F && value <= MAX_PERSISTED_HEALTH
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Villager health is outside the supported range"));
    private static final Codec<Integer> DEPARTURE_COUNTER_CODEC =
            Codec.intRange(0, MAX_PERSISTED_DEPARTURE_COUNTER);
    private static final Codec<Long> GAME_TIME_CODEC = Codec.LONG.validate(value ->
            value >= 0L && value <= MAX_PERSISTED_GAME_TIME
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Villager verification time is outside the supported range"));

    /** Codec for the durable portion of a villager's village POI record. */
    public static final Codec<VillagerPOIRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            net.minecraft.core.UUIDUtil.CODEC.fieldOf("villager_uuid").forGetter(VillagerPOIRecord::getVillagerUUID),
            DISPLAY_NAME_CODEC.optionalFieldOf("display_name", "")
                    .forGetter(record -> record.displayName == null ? "" : record.displayName),
            PROFESSION_CODEC.optionalFieldOf("profession", "")
                    .forGetter(record -> record.profession == null ? "" : record.profession),
            BlockPos.CODEC.optionalFieldOf("bed_position")
                    .forGetter(record -> Optional.ofNullable(record.bedPos)),
            BlockPos.CODEC.optionalFieldOf("job_site_position")
                    .forGetter(record -> Optional.ofNullable(record.jobSitePos)),
            net.minecraft.core.UUIDUtil.CODEC.optionalFieldOf("family_id")
                    .forGetter(record -> Optional.ofNullable(record.familyId)),
            HEALTH_CODEC.optionalFieldOf("health", 0.0F).forGetter(VillagerPOIRecord::getHealth),
            STATUS_CODEC.optionalFieldOf("status", Status.ACTIVE).forGetter(VillagerPOIRecord::getStatus),
            DEPARTURE_COUNTER_CODEC.optionalFieldOf("departure_counter", 0)
                    .forGetter(VillagerPOIRecord::getDepartureCounter),
            GAME_TIME_CODEC.optionalFieldOf("last_verified_tick", 0L)
                    .forGetter(VillagerPOIRecord::getLastVerifiedTick)
    ).apply(instance, VillagerPOIRecord::fromCodec));

    private static Codec<String> boundedStringCodec(int maxLength, String description) {
        return Codec.STRING.validate(value -> value.length() <= maxLength
                ? DataResult.success(value)
                : DataResult.error(() -> description + " exceeds " + maxLength + " characters"));
    }

    private final UUID villagerUUID;
    private String displayName;
    private String profession;
    @Nullable
    private BlockPos bedPos;
    @Nullable
    private BlockPos jobSitePos;
    @Nullable
    private UUID familyId;
    private float health;
    /** Viewer-specific reputation; this is populated in network snapshots and is not persisted. */
    private int opinionOfPlayer;
    private Status status;
    private int departureCounter;
    private long lastVerifiedTick;

    public VillagerPOIRecord(UUID villagerUUID, String displayName, String profession,
                             @Nullable BlockPos bedPos, @Nullable BlockPos jobSitePos,
                             @Nullable UUID familyId, float health, Status status,
                             int departureCounter, long lastVerifiedTick) {
        this.villagerUUID = villagerUUID;
        this.displayName = ProtocolStringLimits.clamp(
                displayName, ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH);
        this.profession = ProtocolStringLimits.clamp(
                profession, ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);
        this.bedPos = bedPos;
        this.jobSitePos = jobSitePos;
        this.familyId = familyId;
        this.health = requireHealth(health);
        this.opinionOfPlayer = 0;
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.departureCounter = requireDepartureCounter(departureCounter);
        this.lastVerifiedTick = requireGameTime(lastVerifiedTick);
    }

    private static VillagerPOIRecord fromCodec(
            UUID villagerUUID,
            String displayName,
            String profession,
            Optional<BlockPos> bedPos,
            Optional<BlockPos> jobSitePos,
            Optional<UUID> familyId,
            float health,
            Status status,
            int departureCounter,
            long lastVerifiedTick
    ) {
        return new VillagerPOIRecord(
                villagerUUID,
                displayName,
                profession,
                bedPos.orElse(null),
                jobSitePos.orElse(null),
                familyId.orElse(null),
                health,
                status,
                departureCounter,
                lastVerifiedTick
        );
    }

    // Getters

    public UUID getVillagerUUID() {
        return villagerUUID;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getProfession() {
        return profession;
    }

    @Nullable
    public BlockPos getBedPos() {
        return bedPos;
    }

    @Nullable
    public BlockPos getJobSitePos() {
        return jobSitePos;
    }

    @Nullable
    public UUID getFamilyId() {
        return familyId;
    }

    public float getHealth() {
        return health;
    }

    /** Returns the current viewer's reputation with this villager. */
    public int getOpinionOfPlayer() {
        return opinionOfPlayer;
    }

    public Status getStatus() {
        return status;
    }

    public int getDepartureCounter() {
        return departureCounter;
    }

    public long getLastVerifiedTick() {
        return lastVerifiedTick;
    }

    // Setters

    public void setDisplayName(String displayName) {
        this.displayName = ProtocolStringLimits.clamp(
                displayName, ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH);
    }

    public void setProfession(String profession) {
        this.profession = ProtocolStringLimits.clamp(
                profession, ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);
    }

    public void setBedPos(@Nullable BlockPos bedPos) {
        this.bedPos = bedPos;
    }

    public void setJobSitePos(@Nullable BlockPos jobSitePos) {
        this.jobSitePos = jobSitePos;
    }

    public void setFamilyId(@Nullable UUID familyId) {
        this.familyId = familyId;
    }

    public void setHealth(float health) {
        this.health = requireHealth(health);
    }

    /** Sets the viewer-specific reputation used by the current ledger snapshot. */
    public void setOpinionOfPlayer(int opinionOfPlayer) {
        this.opinionOfPlayer = opinionOfPlayer;
    }

    /** Creates a display copy carrying a viewer-specific reputation without mutating saved village state. */
    public VillagerPOIRecord copyWithOpinionOfPlayer(int opinionOfPlayer) {
        VillagerPOIRecord copy = new VillagerPOIRecord(
                villagerUUID, displayName, profession, bedPos, jobSitePos, familyId, health,
                status, departureCounter, lastVerifiedTick);
        copy.setOpinionOfPlayer(opinionOfPlayer);
        return copy;
    }

    /** Creates a display copy with only the frequently changing values replaced. */
    public VillagerPOIRecord copyWithDynamicState(float health, int opinionOfPlayer) {
        VillagerPOIRecord copy = new VillagerPOIRecord(
                villagerUUID, displayName, profession, bedPos, jobSitePos, familyId, health,
                status, departureCounter, lastVerifiedTick);
        copy.setOpinionOfPlayer(opinionOfPlayer);
        return copy;
    }

    public void setStatus(Status status) {
        this.status = java.util.Objects.requireNonNull(status, "status");
    }

    public void setDepartureCounter(int departureCounter) {
        this.departureCounter = requireDepartureCounter(departureCounter);
    }

    public void setLastVerifiedTick(long lastVerifiedTick) {
        this.lastVerifiedTick = requireGameTime(lastVerifiedTick);
    }

    private static float requireHealth(float value) {
        if (!Float.isFinite(value) || value < 0.0F || value > MAX_PERSISTED_HEALTH) {
            throw new IllegalArgumentException("Villager health is outside the supported range");
        }
        return value;
    }

    private static int requireDepartureCounter(int value) {
        if (value < 0 || value > MAX_PERSISTED_DEPARTURE_COUNTER) {
            throw new IllegalArgumentException("Villager departure counter is outside the supported range");
        }
        return value;
    }

    private static long requireGameTime(long value) {
        if (value < 0L || value > MAX_PERSISTED_GAME_TIME) {
            throw new IllegalArgumentException("Villager verification time is outside the supported range");
        }
        return value;
    }

    // NBT Serialization

    public CompoundTag save(CompoundTag tag) {
        DataResult<net.minecraft.nbt.Tag> encoded = CODEC.encodeStart(NbtOps.INSTANCE, this);
        return encoded.resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not encode villager POI data: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .map(encodedTag -> {
                    tag.merge(encodedTag);
                    return tag;
                })
                .orElse(tag);
    }

    public static VillagerPOIRecord load(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Could not decode villager POI data: {}", message))
                .orElseThrow(() -> new IllegalArgumentException("Invalid villager POI data"));
    }

    // Network Serialization

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(villagerUUID);
        buf.writeUtf(ProtocolStringLimits.clamp(displayName, ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH),
                ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH);
        buf.writeUtf(ProtocolStringLimits.clamp(profession, ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH),
                ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);

        buf.writeBoolean(bedPos != null);
        if (bedPos != null) {
            buf.writeBlockPos(bedPos);
        }

        buf.writeBoolean(jobSitePos != null);
        if (jobSitePos != null) {
            buf.writeBlockPos(jobSitePos);
        }

        buf.writeBoolean(familyId != null);
        if (familyId != null) {
            buf.writeUUID(familyId);
        }
        buf.writeFloat(health);
        buf.writeVarInt(opinionOfPlayer);

        buf.writeEnum(status);
        buf.writeVarInt(departureCounter);
        buf.writeLong(lastVerifiedTick);
    }

    public static VillagerPOIRecord fromNetwork(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        String name = buf.readUtf(ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH);
        String prof = buf.readUtf(ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);

        BlockPos bed = buf.readBoolean() ? buf.readBlockPos() : null;
        BlockPos job = buf.readBoolean() ? buf.readBlockPos() : null;
        UUID family = buf.readBoolean() ? buf.readUUID() : null;
        float health = buf.readFloat();
        int opinionOfPlayer = buf.readVarInt();

        Status status = buf.readEnum(Status.class);
        int departure = buf.readVarInt();
        long verified = buf.readLong();

        VillagerPOIRecord record = new VillagerPOIRecord(uuid, name, prof, bed, job, family, health, status, departure, verified);
        record.setOpinionOfPlayer(opinionOfPlayer);
        return record;
    }
}
