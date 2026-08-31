package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server response containing only values expected to change while the ledger is open. */
public record VillagePOIDynamicDataPacket(
        boolean hasData,
        UUID villageId,
        boolean scanInProgress,
        boolean hasCompletedScan,
        List<VillagerState> villagers,
        int ironGolemsPresent,
        int emeraldGolemsPresent,
        int emeraldGolemCapacity,
        int villageOpinionOfPlayer,
        VillageRelationship relationship,
        boolean canBecomeGovernorCandidate
) implements CustomPacketPayload {

    public static final int MAX_VILLAGER_STATES = 4096;

    public VillagePOIDynamicDataPacket(boolean hasData, UUID villageId, boolean scanInProgress,
                                        boolean hasCompletedScan, List<VillagerState> villagers,
                                        int ironGolemsPresent, int emeraldGolemsPresent,
                                        int emeraldGolemCapacity, int villageOpinionOfPlayer) {
        this(hasData, villageId, scanInProgress, hasCompletedScan, villagers, ironGolemsPresent,
                emeraldGolemsPresent, emeraldGolemCapacity, villageOpinionOfPlayer,
                VillageRelationship.NEUTRAL, false);
    }

    public static final Type<VillagePOIDynamicDataPacket> TYPE =
            new Type<>(ModIds.id("village_poi_dynamic_data"));

    public static final StreamCodec<FriendlyByteBuf, VillagePOIDynamicDataPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull VillagePOIDynamicDataPacket decode(FriendlyByteBuf buf) {
                    if (!buf.readBoolean()) return empty();
                    UUID villageId = buf.readUUID();
                    boolean scanInProgress = buf.readBoolean();
                    boolean hasCompletedScan = buf.readBoolean();
                    int size = VillagePOIDataPacket.readCount(
                            buf, "dynamic villager states", MAX_VILLAGER_STATES);
                    List<VillagerState> villagers = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        villagers.add(new VillagerState(buf.readUUID(), buf.readFloat(), buf.readVarInt()));
                    }
                    return new VillagePOIDynamicDataPacket(true, villageId, scanInProgress, hasCompletedScan,
                            villagers, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            VillageRelationship.fromNetworkId(buf.readVarInt()), buf.readBoolean());
                }

                @Override
                public void encode(FriendlyByteBuf buf, VillagePOIDynamicDataPacket packet) {
                    buf.writeBoolean(packet.hasData());
                    if (!packet.hasData()) return;
                    buf.writeUUID(packet.villageId());
                    buf.writeBoolean(packet.scanInProgress());
                    buf.writeBoolean(packet.hasCompletedScan());
                    int size = Math.min(packet.villagers().size(), MAX_VILLAGER_STATES);
                    buf.writeVarInt(size);
                    for (int i = 0; i < size; i++) {
                        VillagerState villager = packet.villagers().get(i);
                        buf.writeUUID(villager.villagerId());
                        buf.writeFloat(villager.health());
                        buf.writeVarInt(villager.opinionOfPlayer());
                    }
                    buf.writeVarInt(packet.ironGolemsPresent());
                    buf.writeVarInt(packet.emeraldGolemsPresent());
                    buf.writeVarInt(packet.emeraldGolemCapacity());
                    buf.writeVarInt(packet.villageOpinionOfPlayer());
                    buf.writeVarInt(packet.relationship().ordinal());
                    buf.writeBoolean(packet.canBecomeGovernorCandidate());
                }
            };

    public static VillagePOIDynamicDataPacket build(VillageRecord village, ServerLevel level,
                                                     ServerPlayer viewer, boolean isOp) {
        List<VillagerState> villagers = new ArrayList<>();
        if (isOp || !Config.redactNonOpVillagePoiDetails) {
            for (VillagerPOIRecord record : village.getMembers().values()) {
                Entity entity = level.getEntity(record.getVillagerUUID());
                if (entity instanceof Villager villager && villager.isAlive()) {
                    villagers.add(new VillagerState(record.getVillagerUUID(), villager.getHealth(),
                            villager.getPlayerReputation(viewer)));
                } else {
                    villagers.add(new VillagerState(record.getVillagerUUID(), record.getHealth(), 0));
                }
            }
        }

        int ironGolems = 0;
        int emeraldGolems = 0;
        for (IronGolem golem : level.getEntitiesOfClass(
                IronGolem.class, village.getBoundingBox(), IronGolem::isAlive)) {
            if (golem instanceof EmeraldGolem) emeraldGolems++;
            else ironGolems++;
        }
        var bank = VillagePOIDataFactory.resolveBank(village, level);
        int villageOpinion = village.getVillageOpinion(level, viewer);
        VillageRelationship relationship = village.getPlayerRelationship(level, viewer);
        return new VillagePOIDynamicDataPacket(true, village.getVillageId(),
                village.isFullScanInProgress(), village.isCacheInitialized(), villagers,
                ironGolems, emeraldGolems,
                bank == null ? 0 : bank.getExpectedEmeraldGolemCount(),
                villageOpinion,
                relationship,
                village.getGovernorCandidateId() == null
                        && relationship == VillageRelationship.NEUTRAL
                        && VillageRelationship.canBecomeGovernorCandidate(
                        villageOpinion,
                        Config.governorCandidateOpinionThreshold)
                        && VillageGovernance.hasLivingMayor(level, village));
    }

    public static VillagePOIDynamicDataPacket empty() {
        return new VillagePOIDynamicDataPacket(false, new UUID(0L, 0L), false, false,
                List.of(), 0, 0, 0, 0, VillageRelationship.NEUTRAL, false);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VillagePOIDynamicDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!packet.hasData()) {
                VillagePOIClientCache.clear();
                return;
            }
            VillagePOIClientCache.updateDynamic(packet);
        });
    }

    public record VillagerState(UUID villagerId, float health, int opinionOfPlayer) {
    }
}
