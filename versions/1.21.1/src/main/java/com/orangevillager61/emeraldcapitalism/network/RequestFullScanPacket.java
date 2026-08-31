package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.event.VillageRegistryEvents;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryManager;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client → Server: Requests a full bounding-box re-scan of the player's
 * current village (beds + job sites), then pushes fresh data back.
 */
public record RequestFullScanPacket() implements CustomPacketPayload {

    private static final Map<UUID, Long> LAST_FULL_SCAN_TICK_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<VillageKey, Long> LAST_FULL_SCAN_TICK_BY_VILLAGE = new ConcurrentHashMap<>();

    private record VillageKey(net.minecraft.resources.ResourceKey<Level> dimension, UUID villageId) {}

    public static final Type<RequestFullScanPacket> TYPE =
            new Type<>(ModIds.id("request_full_scan"));

    public static final StreamCodec<ByteBuf, RequestFullScanPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestFullScanPacket());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestFullScanPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "request_full_scan", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            VillageRegistryData data = VillageRegistryData.get(level);
            VillageRecord village = data.getVillageFor(player.blockPosition());

            if (village == null) {
                context.reply(VillagePOIDataPacket.empty());
                return;
            }
            if (!VillagePOIAccessPolicy.isMutationContextValid(player, level, village)) {
                context.reply(VillagePOIDataPacket.empty());
                return;
            }

            long now = level.getGameTime();
            Long lastScanTick = LAST_FULL_SCAN_TICK_BY_PLAYER.get(player.getUUID());
            if (lastScanTick != null && now - lastScanTick < com.orangevillager61.emeraldcapitalism.Config.manualFullScanPlayerCooldownTicks) {
                long remainingTicks = com.orangevillager61.emeraldcapitalism.Config.manualFullScanPlayerCooldownTicks - (now - lastScanTick);
                double remainingSeconds = remainingTicks / 20.0D;
                player.sendSystemMessage(Component.literal(String.format(
                        "[ECAP] Please wait %.1f seconds before requesting another full scan.",
                        remainingSeconds
                )));
                context.reply(buildDeniedResponse(village, level, player));
                return;
            }

            VillageKey villageKey = new VillageKey(level.dimension(), village.getVillageId());
            Long lastVillageScanTick = LAST_FULL_SCAN_TICK_BY_VILLAGE.get(villageKey);
            if (lastVillageScanTick != null && now - lastVillageScanTick < com.orangevillager61.emeraldcapitalism.Config.manualScanPerVillageCooldownTicks) {
                long remainingTicks = com.orangevillager61.emeraldcapitalism.Config.manualScanPerVillageCooldownTicks - (now - lastVillageScanTick);
                player.sendSystemMessage(Component.literal(String.format(
                        "[ECAP] This village was scanned recently. Try again in %.1f seconds.",
                        remainingTicks / 20.0D
                )));
                context.reply(buildDeniedResponse(village, level, player));
                return;
            }

            ManualVillageScanBudget.AcquireResult budget = ManualVillageScanBudget.tryAcquire(level);
            if (!budget.granted()) {
                player.sendSystemMessage(Component.literal(String.format(
                        "[ECAP] Village scans are busy. Try again in %.1f seconds.",
                        budget.retryAfterTicks() / 20.0D
                )));
                context.reply(buildDeniedResponse(village, level, player));
                return;
            }

            VillageRegistryManager manager = VillageRegistryEvents.getManager(level);
            manager.requestFullScan(village, player);
            VillagePOIDataCache.invalidateVillage(village.getVillageId());
            LAST_FULL_SCAN_TICK_BY_PLAYER.put(player.getUUID(), now);
            LAST_FULL_SCAN_TICK_BY_VILLAGE.put(villageKey, now);

            player.sendSystemMessage(Component.literal("[ECAP] Village scan queued."));
            context.reply(buildResponse(village, level, player));
        });
    }

    public static void onPlayerDisconnect(UUID playerId) {
        LAST_FULL_SCAN_TICK_BY_PLAYER.remove(playerId);
    }

    public static void clearCooldowns() {
        LAST_FULL_SCAN_TICK_BY_PLAYER.clear();
        LAST_FULL_SCAN_TICK_BY_VILLAGE.clear();
    }

    private static VillagePOIDataPacket buildResponse(VillageRecord village, ServerLevel level, ServerPlayer player) {
        boolean isOp = player.hasPermissions(com.orangevillager61.emeraldcapitalism.Config.villageCommandPermissionLevel);
        return VillagePOIDataFactory.build(village, level, isOp, player);
    }

    private static VillagePOIDataPacket buildDeniedResponse(VillageRecord village, ServerLevel level, ServerPlayer player) {
        boolean isOp = player.hasPermissions(com.orangevillager61.emeraldcapitalism.Config.villageCommandPermissionLevel);
        return VillagePOIDataFactory.build(village, level, isOp, player);
    }
}
