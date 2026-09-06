package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client request for the small, frequently-changing part of a POI snapshot. */
public record RequestVillagePOIDynamicDataPacket(
        UUID villageId,
        boolean clientHasCompletedScan
) implements CustomPacketPayload {

    private static final long REQUEST_COOLDOWN_TICKS = 10L;
    private static final Map<UUID, DynamicRequestState> REQUEST_STATES = new ConcurrentHashMap<>();

    private record DynamicRequestState(long lastRequestTick, UUID villageId,
                                      VillagePOIDynamicDataPacket response) {
    }

    public static final Type<RequestVillagePOIDynamicDataPacket> TYPE =
            new Type<>(ModIds.id("request_village_poi_dynamic_data"));

    public static final StreamCodec<FriendlyByteBuf, RequestVillagePOIDynamicDataPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull RequestVillagePOIDynamicDataPacket decode(FriendlyByteBuf buf) {
                    return new RequestVillagePOIDynamicDataPacket(buf.readUUID(), buf.readBoolean());
                }

                @Override
                public void encode(FriendlyByteBuf buf, RequestVillagePOIDynamicDataPacket packet) {
                    buf.writeUUID(packet.villageId());
                    buf.writeBoolean(packet.clientHasCompletedScan());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestVillagePOIDynamicDataPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "request_village_poi_dynamic_data", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            VillageRecord village = VillageRegistryData.get(level).getVillages().get(packet.villageId());
            if (village == null || !VillagePOIAccessPolicy.isLocalContextValid(player, level, village)) {
                PacketDistributor.sendToPlayer(player, VillagePOIDynamicDataPacket.empty());
                return;
            }

            boolean isOp = player.hasPermissions(Config.villageCommandPermissionLevel);
            long gameTime = level.getGameTime();
            DynamicRequestState previous = REQUEST_STATES.get(player.getUUID());
            if (previous != null
                    && gameTime < previous.lastRequestTick() + REQUEST_COOLDOWN_TICKS) {
                // Re-send the most recent response for the same village. A
                // different village gets an empty delta rather than causing a
                // fresh dynamic scan during the cooldown window.
                PacketDistributor.sendToPlayer(player,
                        previous.villageId().equals(packet.villageId())
                                ? previous.response() : VillagePOIDynamicDataPacket.empty());
                return;
            }
            // A screen opened before its first full scan needs one new static
            // snapshot at the completion transition, then returns to deltas.
            if (!packet.clientHasCompletedScan() && village.isCacheInitialized()) {
                PacketDistributor.sendToPlayer(player,
                        VillagePOIDataCache.getOrBuild(level, village, isOp, player));
                return;
            }

            VillagePOIDynamicDataPacket response = PerformanceTimingCounters.measure(
                    PerformanceTimingCounters.Operation.POI_DYNAMIC_REFRESH,
                    () -> VillagePOIDynamicDataPacket.build(village, level, player, isOp));
            REQUEST_STATES.put(player.getUUID(),
                    new DynamicRequestState(gameTime, packet.villageId(), response));
            PacketDistributor.sendToPlayer(player, response);
        });
    }

    public static void onPlayerDisconnect(UUID playerId) {
        REQUEST_STATES.remove(playerId);
    }
}
