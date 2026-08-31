package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Client request to change one village-owned repair setting. */
public record SetVillageRepairPacket(UUID villageId, int featureId, boolean enabled)
        implements CustomPacketPayload {

    public static final int FARMLAND = 0;
    public static final int DOORS = 1;

    public static final Type<SetVillageRepairPacket> TYPE =
            new Type<>(ModIds.id("set_village_repair"));

    public static final StreamCodec<FriendlyByteBuf, SetVillageRepairPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull SetVillageRepairPacket decode(FriendlyByteBuf buf) {
                    return new SetVillageRepairPacket(buf.readUUID(), buf.readVarInt(), buf.readBoolean());
                }

                @Override
                public void encode(FriendlyByteBuf buf, SetVillageRepairPacket packet) {
                    buf.writeUUID(packet.villageId());
                    buf.writeVarInt(packet.featureId());
                    buf.writeBoolean(packet.enabled());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetVillageRepairPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "set_village_repair", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            VillageRecord village = VillageRegistryData.get(level).getVillages().get(packet.villageId());
            if (village == null) {
                player.sendSystemMessage(Component.literal("[ECAP] Village not found."));
                return;
            }
            if (packet.featureId() != FARMLAND && packet.featureId() != DOORS) {
                player.sendSystemMessage(Component.literal("[ECAP] Unknown village repair setting."));
                return;
            }
            if (!VillagePOIAccessPolicy.isMutationContextValid(player, level, village)
                    || village.getPlayerRelationship(level, player) != VillageRelationship.GOVERNOR) {
                player.sendSystemMessage(Component.literal(
                        "[ECAP] Only the village Governor can change village repair settings."));
                return;
            }

            boolean changed = packet.featureId() == FARMLAND
                    ? village.setFarmlandRepairEnabled(packet.enabled())
                    : village.setDoorRepairEnabled(packet.enabled());
            if (changed) {
                VillageRegistryData.get(level).setDirty();
                VillagePOIDataCache.invalidateVillage(village.getVillageId());
            }

            String feature = packet.featureId() == FARMLAND ? "Farmland" : "Doors";
            player.sendSystemMessage(Component.literal(
                    "[ECAP] " + feature + " repair " + (packet.enabled() ? "enabled" : "disabled") + "."));
            boolean isOp = player.hasPermissions(Config.villageCommandPermissionLevel);
            context.reply(VillagePOIDataFactory.build(village, level, isOp, player));
        });
    }
}
