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
 * Client → Server: Toggles the POI overlay subscription for the sending player.
 * When subscribed, the server pushes a fresh {@link VillagePOIDataPacket} periodically.
 */
public record TogglePOIOverlayPacket(boolean hasVillageId, UUID villageId) implements CustomPacketPayload {

    public static final Type<TogglePOIOverlayPacket> TYPE =
            new Type<>(ModIds.id("toggle_poi_overlay"));

    private static final StreamCodec<ByteBuf, UUID> UUID_STREAM_CODEC = new StreamCodec<>() {
        @Override
        public @NotNull UUID decode(@NotNull ByteBuf buf) {
            return new UUID(buf.readLong(), buf.readLong());
        }

        @Override
        public void encode(@NotNull ByteBuf buf, @NotNull UUID uuid) {
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
        }
    };

    public static final StreamCodec<ByteBuf, TogglePOIOverlayPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, TogglePOIOverlayPacket::hasVillageId,
            UUID_STREAM_CODEC, TogglePOIOverlayPacket::villageId,
            TogglePOIOverlayPacket::new
    );

    /** Preserves the original nearest/current-village toggle behavior. */
    public TogglePOIOverlayPacket() {
        this(false, new UUID(0, 0));
    }

    /** Toggles the overlay while binding a new subscription to this village. */
    public static TogglePOIOverlayPacket forVillage(UUID villageId) {
        return new TogglePOIOverlayPacket(true, villageId);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // Server handler

    public static void handle(TogglePOIOverlayPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "toggle_poi_overlay", player -> {
            UUID playerId = player.getUUID();

            if (POIOverlaySubscriptions.isSubscribed(playerId)) {
                POIOverlaySubscriptions.unsubscribe(playerId);
                EmeraldCapitalism.LOGGER.debug("Player {} unsubscribed from POI overlay", player.getName().getString());
            } else {
                // Resolve an explicitly requested village only through the server registry.
                ServerLevel level = PacketHandlerUtil.serverLevel(player);
                VillageRegistryData data = VillageRegistryData.get(level);
                VillageRecord village = packet.hasVillageId()
                        ? data.getVillages().get(packet.villageId())
                        : data.getVillageFor(player.blockPosition());
                if (village != null && VillagePOIAccessPolicy.isLocalContextValid(player, level, village)) {
                    POIOverlaySubscriptions.subscribe(playerId, village.getVillageId());
                    EmeraldCapitalism.LOGGER.debug("Player {} subscribed to POI overlay for village {}",
                            player.getName().getString(), village.getVillageId());

                    // Push an immediate snapshot so clients do not wait for the periodic sync tick.
                    if (village.verify(level)) {
                        data.setDirty();
                    }
                    if (!village.isCacheInitialized()) {
                        VillageRegistryEvents.getManager(level).requestFullScan(village, player);
                    }
                    boolean isOp = player.hasPermissions(com.orangevillager61.emeraldcapitalism.Config.villageCommandPermissionLevel);
                    context.reply(VillagePOIDataFactory.build(village, level, isOp, player));
                } else {
                    EmeraldCapitalism.LOGGER.debug("Player {} tried to subscribe but the requested village is unavailable",
                            player.getName().getString());
                }
            }
        });
    }
}
