package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.event.VillageRegistryEvents;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Client → Server: Request POI data for a specific village, or the nearest village
 * if no UUID is provided.
 */
public record RequestVillagePOIsPacket(boolean hasVillageId, UUID villageId) implements CustomPacketPayload {

    public static final Type<RequestVillagePOIsPacket> TYPE =
            new Type<>(ModIds.id("request_village_pois"));

    public static final StreamCodec<ByteBuf, RequestVillagePOIsPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, RequestVillagePOIsPacket::hasVillageId,
            new StreamCodec<ByteBuf, UUID>() {
                @Override
                public @NotNull UUID decode(@NotNull ByteBuf buf) {
                    return new UUID(buf.readLong(), buf.readLong());
                }

                @Override
                public void encode(@NotNull ByteBuf buf, @NotNull UUID uuid) {
                    buf.writeLong(uuid.getMostSignificantBits());
                    buf.writeLong(uuid.getLeastSignificantBits());
                }
            }, RequestVillagePOIsPacket::villageId,
            RequestVillagePOIsPacket::new
    );

    /** Convenience factory for "give me the nearest village". */
    public static RequestVillagePOIsPacket nearest() {
        return new RequestVillagePOIsPacket(false, new UUID(0, 0));
    }

    /** Convenience factory for a specific village. */
    public static RequestVillagePOIsPacket forVillage(UUID villageId) {
        return new RequestVillagePOIsPacket(true, villageId);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // Server handler

    public static void handle(RequestVillagePOIsPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "request_village_pois", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            VillageRegistryData data = VillageRegistryData.get(level);

            VillageRecord village;
            if (packet.hasVillageId()) {
                village = data.getVillages().get(packet.villageId());
            } else {
                village = data.getNearestVillage(player.blockPosition());
            }

            if (village == null) {
                // Send empty response
                context.reply(VillagePOIDataPacket.empty());
                return;
            }

            if (!VillagePOIAccessPolicy.isLocalContextValid(player, level, village)) {
                context.reply(VillagePOIDataPacket.empty());
                return;
            }

            // Keep an already-enabled overlay bound to the village whose data the
            // ledger just requested instead of allowing its old periodic target to
            // overwrite the screen after the next push interval.
            if (packet.hasVillageId()) {
                POIOverlaySubscriptions.retargetIfSubscribed(player.getUUID(), village.getVillageId());
            }

            // Verify cached block positions (cheap point-lookups)
            if (village.verify(level)) {
                data.setDirty();
            }
            if (!village.isCacheInitialized()) {
                VillageRegistryEvents.getManager(level).requestFullScan(village, player);
            }

            boolean isOp = player.hasPermissions(com.orangevillager61.emeraldcapitalism.Config.villageCommandPermissionLevel);
            context.reply(VillagePOIDataCache.getOrBuild(level, village, isOp, player));
        });
    }
}
