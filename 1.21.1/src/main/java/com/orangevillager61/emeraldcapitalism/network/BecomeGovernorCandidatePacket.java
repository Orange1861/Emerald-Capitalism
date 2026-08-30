package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Client request to register the sender as a candidate for a village governor role. */
public record BecomeGovernorCandidatePacket(UUID villageId) implements CustomPacketPayload {

    public static final Type<BecomeGovernorCandidatePacket> TYPE =
            new Type<>(ModIds.id("become_governor_candidate"));

    public static final StreamCodec<FriendlyByteBuf, BecomeGovernorCandidatePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull BecomeGovernorCandidatePacket decode(FriendlyByteBuf buf) {
                    return new BecomeGovernorCandidatePacket(buf.readUUID());
                }

                @Override
                public void encode(FriendlyByteBuf buf, BecomeGovernorCandidatePacket packet) {
                    buf.writeUUID(packet.villageId());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BecomeGovernorCandidatePacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "become_governor_candidate", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            VillageRecord village = VillageRegistryData.get(level).getVillages().get(packet.villageId());
            if (village == null || !VillagePOIAccessPolicy.isLocalContextValid(player, level, village)) {
                player.sendSystemMessage(Component.literal("[ECAP] You must be inside that village to apply as a governor candidate."));
                return;
            }

            if (!VillageGovernance.hasLivingMayor(level, village)) {
                player.sendSystemMessage(Component.literal(
                        "[ECAP] A village must have a living Mayor before a governor candidate can be chosen."));
                return;
            }

            int opinion = village.getVillageOpinion(level, player);
            if (!VillageRelationship.canBecomeGovernorCandidate(
                    opinion, Config.governorCandidateOpinionThreshold)) {
                player.sendSystemMessage(Component.literal("[ECAP] You need a village opinion above "
                        + Config.governorCandidateOpinionThreshold
                        + " to become a governor candidate."));
                context.reply(VillagePOIDataFactory.build(village, level,
                        player.hasPermissions(Config.villageCommandPermissionLevel), player));
                return;
            }

            if (village.isGovernorCandidate(player.getUUID())) {
                player.sendSystemMessage(Component.literal("[ECAP] You are already a governor candidate."));
                return;
            }

            if (village.getGovernorCandidateId() != null) {
                player.sendSystemMessage(Component.literal(
                        "[ECAP] This village already has a governor candidate."));
                return;
            }

            if (!village.becomeGovernorCandidate(player.getUUID(), opinion)) {
                return;
            }
            boolean promoted = VillageGovernance.refresh(level, village);
            VillageRegistryData.get(level).setDirty();
            VillagePOIDataCache.invalidateVillage(village.getVillageId());
            player.sendSystemMessage(Component.literal(promoted
                    ? "[ECAP] You are now the village Governor."
                    : "[ECAP] You are now a governor candidate."));
            context.reply(VillagePOIDataFactory.build(village, level,
                    player.hasPermissions(Config.villageCommandPermissionLevel), player));
        });
    }
}
