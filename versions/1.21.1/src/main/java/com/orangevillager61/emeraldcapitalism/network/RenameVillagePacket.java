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
 * Client → Server: Rename a village.
 */
public record RenameVillagePacket(UUID villageId, String newName) implements CustomPacketPayload {

    public static final Type<RenameVillagePacket> TYPE =
            new Type<>(ModIds.id("rename_village"));

    public static final StreamCodec<FriendlyByteBuf, RenameVillagePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull RenameVillagePacket decode(FriendlyByteBuf buf) {
                    UUID villageId = buf.readUUID();
                    String name = buf.readUtf(ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
                    return new RenameVillagePacket(villageId, name);
                }

                @Override
                public void encode(FriendlyByteBuf buf, RenameVillagePacket packet) {
                    buf.writeUUID(packet.villageId());
                    buf.writeUtf(ProtocolStringLimits.clamp(packet.newName(), ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH),
                            ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RenameVillagePacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "rename_village", player -> {
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
                player.sendSystemMessage(Component.literal("[ECAP] Only the village Governor can rename this village."));
                return;
            }

            String name = packet.newName() == null ? "" : packet.newName().trim();
            if (name.isEmpty()) {
                player.sendSystemMessage(Component.literal("[ECAP] Village name cannot be empty."));
                return;
            }

            int maxLen = Config.maxVillageNameLength;
            if (name.length() > maxLen) {
                name = name.substring(0, maxLen);
            }

            data.renameVillage(level, village, name);

            if (village.verify(level)) {
                data.setDirty();
            }

            boolean isOp = player.hasPermissions(Config.villageCommandPermissionLevel);
            context.reply(VillagePOIDataFactory.build(village, level, isOp, player));
        });
    }
}
