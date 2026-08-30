package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.JobSiteEntry;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-to-client snapshot for the village POI screen and overlay. */
public record VillagePOIDataPacket(
        boolean hasData,
        Status status,
        Identity identity,
        List<VillagerPOIRecord> records,
        Totals totals,
        RepairData repair,
        EntityCounts entityCounts,
        RelationshipData relationshipData,
        Bounds bounds,
        Messages messages
) implements CustomPacketPayload {

    public static final int MAX_VILLAGER_RECORDS = 4096;
    public static final int MAX_JOB_SITES = 1024;
    public static final int MAX_BED_POSITIONS = 1024;
    public static final int MAX_REPAIR_QUEUE_POSITIONS = 1024;

    public static final Type<VillagePOIDataPacket> TYPE =
            new Type<>(ModIds.id("village_poi_data"));

    public VillagePOIDataPacket {
        status = Objects.requireNonNull(status, "status");
        identity = Objects.requireNonNull(identity, "identity");
        records = List.copyOf(records);
        totals = Objects.requireNonNull(totals, "totals");
        repair = Objects.requireNonNull(repair, "repair");
        entityCounts = Objects.requireNonNull(entityCounts, "entityCounts");
        relationshipData = Objects.requireNonNull(relationshipData, "relationshipData");
        bounds = Objects.requireNonNull(bounds, "bounds");
        messages = Objects.requireNonNull(messages, "messages");
    }

    public static final StreamCodec<FriendlyByteBuf, VillagePOIDataPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull VillagePOIDataPacket decode(FriendlyByteBuf buf) {
                    if (!buf.readBoolean()) {
                        return empty();
                    }

                    Status status = new Status(buf.readBoolean(), buf.readBoolean());
                    Identity identity = new Identity(
                            buf.readUUID(),
                            buf.readUtf(ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH),
                            buf.readBoolean(),
                            buf.readBlockPos());

                    int recordCount = readCount(buf, "villager records", MAX_VILLAGER_RECORDS);
                    List<VillagerPOIRecord> records = new ArrayList<>(recordCount);
                    for (int i = 0; i < recordCount; i++) {
                        records.add(VillagerPOIRecord.fromNetwork(buf));
                    }

                    int totalBeds = buf.readVarInt();
                    int availableBeds = buf.readVarInt();
                    int jobSiteCount = readCount(buf, "job sites", MAX_JOB_SITES);
                    List<JobSiteEntry> jobSites = new ArrayList<>(jobSiteCount);
                    for (int i = 0; i < jobSiteCount; i++) {
                        jobSites.add(JobSiteEntry.fromNetwork(buf));
                    }
                    int bedPositionCount = readCount(buf, "bed positions", MAX_BED_POSITIONS);
                    List<BlockPos> bedPositions = new ArrayList<>(bedPositionCount);
                    for (int i = 0; i < bedPositionCount; i++) {
                        bedPositions.add(buf.readBlockPos());
                    }
                    Totals totals = new Totals(totalBeds, availableBeds, jobSites, bedPositions);

                    RepairData repair = new RepairData(
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readBoolean(), buf.readBoolean(),
                            readBlockPositions(buf, "repair queue positions", MAX_REPAIR_QUEUE_POSITIONS));

                    EntityCounts entityCounts = new EntityCounts(
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
                    RelationshipData relationshipData = new RelationshipData(
                            buf.readVarInt(),
                            VillageRelationship.fromNetworkId(buf.readVarInt()),
                            buf.readBoolean());
                    Bounds bounds = new Bounds(
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble());
                    Messages messages = new Messages(
                            buf.readUtf(ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH),
                            buf.readUtf(ProtocolStringLimits.MAX_BANK_NAME_LENGTH));
                    return new VillagePOIDataPacket(true, status, identity, records, totals, repair,
                            entityCounts, relationshipData, bounds, messages);
                }

                @Override
                public void encode(FriendlyByteBuf buf, VillagePOIDataPacket packet) {
                    buf.writeBoolean(packet.hasData());
                    if (!packet.hasData()) {
                        return;
                    }

                    Status status = packet.status();
                    buf.writeBoolean(status.scanInProgress());
                    buf.writeBoolean(status.hasCompletedScan());
                    Identity identity = packet.identity();
                    buf.writeUUID(identity.villageId());
                    buf.writeUtf(ProtocolStringLimits.clamp(identity.villageName(),
                                    ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH),
                            ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
                    buf.writeBoolean(identity.isOperator());
                    buf.writeBlockPos(identity.bellPosition());

                    writeRecords(buf, packet.records());
                    Totals totals = packet.totals();
                    buf.writeVarInt(totals.totalBeds());
                    buf.writeVarInt(totals.availableBeds());
                    writeJobSites(buf, totals.jobSites());
                    writeBlockPositions(buf, totals.bedPositions(), MAX_BED_POSITIONS);

                    RepairData repair = packet.repair();
                    buf.writeVarInt(repair.farmlandCount());
                    buf.writeVarInt(repair.doorCount());
                    buf.writeVarInt(repair.repairQueueCount());
                    buf.writeBoolean(repair.farmlandRepairEnabled());
                    buf.writeBoolean(repair.doorRepairEnabled());
                    writeBlockPositions(buf, repair.repairQueuePositions(), MAX_REPAIR_QUEUE_POSITIONS);

                    EntityCounts entityCounts = packet.entityCounts();
                    buf.writeVarInt(entityCounts.ironGolemCapacity());
                    buf.writeVarInt(entityCounts.ironGolemsPresent());
                    buf.writeVarInt(entityCounts.emeraldGolemsPresent());
                    buf.writeVarInt(entityCounts.emeraldGolemCapacity());

                    RelationshipData relationshipData = packet.relationshipData();
                    buf.writeVarInt(relationshipData.villageOpinionOfPlayer());
                    buf.writeVarInt(relationshipData.relationship().ordinal());
                    buf.writeBoolean(relationshipData.canBecomeGovernorCandidate());

                    Bounds bounds = packet.bounds();
                    buf.writeDouble(bounds.minX());
                    buf.writeDouble(bounds.minY());
                    buf.writeDouble(bounds.minZ());
                    buf.writeDouble(bounds.maxX());
                    buf.writeDouble(bounds.maxY());
                    buf.writeDouble(bounds.maxZ());
                    Messages messages = packet.messages();
                    buf.writeUtf(ProtocolStringLimits.clamp(messages.welcomeMessage(),
                                    ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH),
                            ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH);
                    buf.writeUtf(ProtocolStringLimits.clamp(messages.bankName(),
                                    ProtocolStringLimits.MAX_BANK_NAME_LENGTH),
                            ProtocolStringLimits.MAX_BANK_NAME_LENGTH);
                }
            };

    public static VillagePOIDataPacket empty() {
        return new VillagePOIDataPacket(false, new Status(false, false),
                new Identity(new UUID(0, 0), "", false, BlockPos.ZERO), List.of(),
                new Totals(0, 0, List.of(), List.of()),
                new RepairData(0, 0, 0, false, false, List.of()),
                new EntityCounts(0, 0, 0, 0),
                new RelationshipData(0, VillageRelationship.NEUTRAL, false),
                new Bounds(0, 0, 0, 0, 0, 0), new Messages("", ""));
    }

    public VillagePOIDataPacket withDynamicState(List<VillagerPOIRecord> updatedRecords,
                                                  VillagePOIDynamicDataPacket dynamic) {
        return new VillagePOIDataPacket(
                hasData,
                new Status(dynamic.scanInProgress(), dynamic.hasCompletedScan()),
                identity,
                updatedRecords,
                totals,
                repair,
                new EntityCounts(entityCounts.ironGolemCapacity(), dynamic.ironGolemsPresent(),
                        dynamic.emeraldGolemsPresent(), dynamic.emeraldGolemCapacity()),
                new RelationshipData(dynamic.villageOpinionOfPlayer(), dynamic.relationship(),
                        dynamic.canBecomeGovernorCandidate()),
                bounds,
                messages);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VillagePOIDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (packet.hasData()) {
                VillagePOIClientCache.update(packet);
            } else {
                VillagePOIClientCache.clear();
            }
        });
    }

    private static void writeRecords(FriendlyByteBuf buf, List<VillagerPOIRecord> records) {
        int count = Math.min(records.size(), MAX_VILLAGER_RECORDS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            records.get(i).toNetwork(buf);
        }
    }

    private static void writeJobSites(FriendlyByteBuf buf, List<JobSiteEntry> jobSites) {
        int count = Math.min(jobSites.size(), MAX_JOB_SITES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            jobSites.get(i).toNetwork(buf);
        }
    }

    private static void writeBlockPositions(FriendlyByteBuf buf, List<BlockPos> positions, int max) {
        int count = Math.min(positions.size(), max);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeBlockPos(positions.get(i));
        }
    }

    private static List<BlockPos> readBlockPositions(FriendlyByteBuf buf, String description, int max) {
        int count = readCount(buf, description, max);
        List<BlockPos> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            positions.add(buf.readBlockPos());
        }
        return List.copyOf(positions);
    }

    static int readCount(FriendlyByteBuf buf, String description, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("Invalid " + description + " count: " + count);
        }
        return count;
    }

    public record Status(boolean scanInProgress, boolean hasCompletedScan) {
    }

    public record Identity(UUID villageId, String villageName, boolean isOperator,
                           BlockPos bellPosition) {
        public Identity {
            villageId = Objects.requireNonNull(villageId, "villageId");
            villageName = Objects.requireNonNull(villageName, "villageName");
            bellPosition = Objects.requireNonNull(bellPosition, "bellPosition").immutable();
        }
    }

    public record Totals(int totalBeds, int availableBeds, List<JobSiteEntry> jobSites,
                         List<BlockPos> bedPositions) {
        public Totals {
            jobSites = List.copyOf(jobSites);
            bedPositions = List.copyOf(bedPositions);
        }
    }

    public record RepairData(int farmlandCount, int doorCount, int repairQueueCount,
                             boolean farmlandRepairEnabled, boolean doorRepairEnabled,
                             List<BlockPos> repairQueuePositions) {
        public RepairData {
            repairQueuePositions = List.copyOf(repairQueuePositions);
        }
    }

    public record EntityCounts(int ironGolemCapacity, int ironGolemsPresent,
                               int emeraldGolemsPresent, int emeraldGolemCapacity) {
    }

    public record RelationshipData(int villageOpinionOfPlayer, VillageRelationship relationship,
                                   boolean canBecomeGovernorCandidate) {
        public RelationshipData {
            relationship = Objects.requireNonNull(relationship, "relationship");
        }
    }

    public record Bounds(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {
    }

    public record Messages(String welcomeMessage, String bankName) {
        public Messages {
            welcomeMessage = Objects.requireNonNull(welcomeMessage, "welcomeMessage");
            bankName = Objects.requireNonNull(bankName, "bankName");
        }
    }
}
