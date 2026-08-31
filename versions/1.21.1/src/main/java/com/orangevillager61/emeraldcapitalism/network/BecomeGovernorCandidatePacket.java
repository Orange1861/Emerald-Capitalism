package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookDefinition;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRarity;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookStackFactory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
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
                sendLedgerRefresh(player, village, level);
                return;
            }

            int opinion = village.getVillageOpinion(level, player);
            if (!VillageRelationship.canBecomeGovernorCandidate(
                    opinion, Config.governorCandidateOpinionThreshold)) {
                player.sendSystemMessage(Component.literal("[ECAP] You need a village opinion above "
                        + Config.governorCandidateOpinionThreshold
                        + " to become a governor candidate."));
                sendLedgerRefresh(player, village, level);
                return;
            }

            if (village.isGovernorCandidate(player.getUUID())) {
                player.sendSystemMessage(Component.literal("[ECAP] You are already a governor candidate."));
                sendLedgerRefresh(player, village, level);
                return;
            }

            if (village.getGovernorCandidateId() != null) {
                player.sendSystemMessage(Component.literal(
                        "[ECAP] This village already has a governor candidate."));
                sendLedgerRefresh(player, village, level);
                return;
            }

            LibraryBookDefinition managerBook = LibraryBookRegistry.entries(LibraryBookRarity.VILLAGE_MANAGER)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (managerBook == null) {
                EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Cannot complete governor claim: no Village Manager book is loaded.");
                player.sendSystemMessage(Component.literal(
                        "[ECAP] The Village Manager book is unavailable; try again after resources reload."));
                sendLedgerRefresh(player, village, level);
                return;
            }

            if (!village.becomeGovernorCandidate(player.getUUID(), opinion)) {
                sendLedgerRefresh(player, village, level);
                return;
            }
            boolean promoted = VillageGovernance.refresh(level, village);
            VillageRegistryData.get(level).setDirty();
            VillagePOIDataCache.invalidateVillage(village.getVillageId());
            ItemStack book = LibraryBookStackFactory.createItemStack(managerBook);
            if (!player.getInventory().add(book)) {
                player.drop(book, false);
            }
            player.sendSystemMessage(Component.literal(promoted
                    ? "[ECAP] You are now the village Governor."
                    : "[ECAP] You are now a governor candidate."));
            sendLedgerRefresh(player, village, level);
        });
    }

    /** Pushes the post-action state so an open ledger cannot keep a stale relationship. */
    private static void sendLedgerRefresh(ServerPlayer player, VillageRecord village, ServerLevel level) {
        PacketDistributor.sendToPlayer(player, VillagePOIDataFactory.build(village, level,
                player.hasPermissions(Config.villageCommandPermissionLevel), player));
    }
}
