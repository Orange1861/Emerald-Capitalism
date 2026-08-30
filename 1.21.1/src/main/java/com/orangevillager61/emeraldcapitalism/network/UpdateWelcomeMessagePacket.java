package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Client → Server: Update a village's welcome message.
 */
public record UpdateWelcomeMessagePacket(UUID villageId, String welcomeMessage) implements CustomPacketPayload {

    public static final Type<UpdateWelcomeMessagePacket> TYPE =
            new Type<>(ModIds.id("update_welcome_message"));

    public static final StreamCodec<FriendlyByteBuf, UpdateWelcomeMessagePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull UpdateWelcomeMessagePacket decode(FriendlyByteBuf buf) {
                    UUID villageId = buf.readUUID();
                    String message = buf.readUtf(ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH);
                    return new UpdateWelcomeMessagePacket(villageId, message);
                }

                @Override
                public void encode(FriendlyByteBuf buf, UpdateWelcomeMessagePacket packet) {
                    buf.writeUUID(packet.villageId());
                    buf.writeUtf(ProtocolStringLimits.clamp(packet.welcomeMessage(), ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH),
                            ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH);
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UpdateWelcomeMessagePacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "update_welcome_message", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            VillageRegistryData data = VillageRegistryData.get(level);

            VillageRecord village = data.getVillages().get(packet.villageId());
            if (village == null) {
                player.sendSystemMessage(Component.literal("[ECAP] Village not found."));
                return;
            }
            if (!VillagePOIAccessPolicy.isLocalContextValid(player, level, village)
                    || village.getPlayerRelationship(level, player)
                    != VillageRelationship.GOVERNOR) {
                player.sendSystemMessage(Component.literal("[ECAP] Only the village Governor can change the welcome message."));
                return;
            }

            String message = packet.welcomeMessage() == null ? "" : packet.welcomeMessage().trim();
            village.setWelcomeMessage(ProtocolStringLimits.clamp(
                    message, ProtocolStringLimits.MAX_WELCOME_MESSAGE_LENGTH));
            data.setDirty();
            VillagePOIDataCache.invalidateVillage(village.getVillageId());

            if (message.isEmpty()) {
                player.sendSystemMessage(Component.literal("[ECAP] Welcome message disabled."));
            } else {
                player.sendSystemMessage(Component.literal("[ECAP] Welcome message updated."));
            }

            // Send updated data back to the client
            if (village.verify(level)) {
                data.setDirty();
            }
            boolean isOp = player.hasPermissions(Config.villageCommandPermissionLevel);
            context.reply(VillagePOIDataFactory.build(village, level, isOp, player));
        });
    }
}
