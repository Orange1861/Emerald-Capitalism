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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client → Server: Expands the village bounding box back to the full
 * 128×32×128 scan area, performs a full re-scan (which will shrink the
 * box to fit discovered blocks), and pushes fresh data back to the client.
 * <p>
 * This lets players grow the village box after adding structures beyond
 * the current (shrunk) bounds. Cooldowns are configurable in common config.
 */
public record RequestExpandBoundsPacket() implements CustomPacketPayload {

    private static final Map<UUID, Long> LAST_EXPAND_TICK_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<VillageKey, Long> LAST_EXPAND_TICK_BY_VILLAGE = new ConcurrentHashMap<>();

    private record VillageKey(net.minecraft.resources.ResourceKey<Level> dimension, UUID villageId) {}

    public static final Type<RequestExpandBoundsPacket> TYPE =
            new Type<>(ModIds.id("request_expand_bounds"));

    public static final StreamCodec<ByteBuf, RequestExpandBoundsPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestExpandBoundsPacket());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestExpandBoundsPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "request_expand_bounds", player -> {
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
            Long lastExpandTick = LAST_EXPAND_TICK_BY_PLAYER.get(player.getUUID());
            if (lastExpandTick != null && now - lastExpandTick < com.orangevillager61.emeraldcapitalism.Config.manualExpandBoundsPlayerCooldownTicks) {
                long remainingTicks = com.orangevillager61.emeraldcapitalism.Config.manualExpandBoundsPlayerCooldownTicks - (now - lastExpandTick);
                double remainingSeconds = remainingTicks / 20.0D;
                player.sendSystemMessage(Component.literal(String.format(
                        "[ECAP] Please wait %.0f seconds before expanding bounds again.",
                        remainingSeconds
                )));
                context.reply(buildDeniedResponse(village, level, player));
                return;
            }

            VillageKey villageKey = new VillageKey(level.dimension(), village.getVillageId());
            Long lastVillageExpandTick = LAST_EXPAND_TICK_BY_VILLAGE.get(villageKey);
            if (lastVillageExpandTick != null && now - lastVillageExpandTick < com.orangevillager61.emeraldcapitalism.Config.manualScanPerVillageCooldownTicks) {
                long remainingTicks = com.orangevillager61.emeraldcapitalism.Config.manualScanPerVillageCooldownTicks - (now - lastVillageExpandTick);
                player.sendSystemMessage(Component.literal(String.format(
                        "[ECAP] This village was updated recently. Try again in %.1f seconds.",
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

            // Reset bounding box to full scan area, then fullScan will shrink to fit
            AABB fullArea = new AABB(village.getBellPosition()).inflate(128, 48, 128);
            village.setBoundingBox(fullArea);
            data.setDirty();

            VillageRegistryManager manager = VillageRegistryEvents.getManager(level);
            manager.restartFullScan(village, player);
            VillagePOIDataCache.invalidateVillage(village.getVillageId());
            LAST_EXPAND_TICK_BY_PLAYER.put(player.getUUID(), now);
            LAST_EXPAND_TICK_BY_VILLAGE.put(villageKey, now);

            player.sendSystemMessage(Component.literal("[ECAP] Village bounds expanded; scan queued."));
            context.reply(buildResponse(village, level, player));
        });
    }

    public static void onPlayerDisconnect(UUID playerId) {
        LAST_EXPAND_TICK_BY_PLAYER.remove(playerId);
    }

    public static void clearCooldowns() {
        LAST_EXPAND_TICK_BY_PLAYER.clear();
        LAST_EXPAND_TICK_BY_VILLAGE.clear();
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
