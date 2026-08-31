package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.event.VillageRegistryEvents;
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

/** Client request to rebuild the selected village's tracked block caches. */
public record ResetVillageCachePacket(UUID villageId, int featureId) implements CustomPacketPayload {

    public static final Type<ResetVillageCachePacket> TYPE =
            new Type<>(ModIds.id("reset_village_cache"));

    public static final StreamCodec<FriendlyByteBuf, ResetVillageCachePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull ResetVillageCachePacket decode(FriendlyByteBuf buf) {
                    return new ResetVillageCachePacket(buf.readUUID(), buf.readVarInt());
                }

                @Override
                public void encode(FriendlyByteBuf buf, ResetVillageCachePacket packet) {
                    buf.writeUUID(packet.villageId());
                    buf.writeVarInt(packet.featureId());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ResetVillageCachePacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "reset_village_cache", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            VillageRegistryData data = VillageRegistryData.get(level);
            VillageRecord village = data.getVillages().get(packet.villageId());
            if (village == null) {
                player.sendSystemMessage(Component.literal("[ECAP] Village not found."));
                return;
            }
            if (packet.featureId() != SetVillageRepairPacket.FARMLAND
                    && packet.featureId() != SetVillageRepairPacket.DOORS) {
                player.sendSystemMessage(Component.literal("[ECAP] Unknown village cache."));
                return;
            }
            if (!VillagePOIAccessPolicy.isMutationContextValid(player, level, village)
                    || village.getPlayerRelationship(level, player) != VillageRelationship.GOVERNOR) {
                player.sendSystemMessage(Component.literal(
                        "[ECAP] Only the village Governor can reset village caches."));
                return;
            }

            boolean queued = VillageRegistryEvents.getManager(level).requestFullScan(village, player);
            if (queued && packet.featureId() == SetVillageRepairPacket.DOORS && village.clearMissingDoors()) {
                data.setDirty();
            }
            VillagePOIDataCache.invalidateVillage(village.getVillageId());
            String feature = packet.featureId() == SetVillageRepairPacket.FARMLAND ? "Farmland" : "Doors";
            player.sendSystemMessage(Component.literal(queued
                    ? "[ECAP] " + feature + " cache reset; village scan queued."
                    : "[ECAP] A village cache scan is already in progress."));
            boolean isOp = player.hasPermissions(Config.villageCommandPermissionLevel);
            context.reply(VillagePOIDataFactory.build(village, level, isOp, player));
        });
    }
}
