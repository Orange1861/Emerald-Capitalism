package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Consumer;

/** Utility helpers for safe server-side packet handling. */
public final class PacketHandlerUtil {

    private PacketHandlerUtil() {}

    /** Returns the authoritative level currently attached to a server player. */
    public static ServerLevel serverLevel(ServerPlayer player) {
        return player.serverLevel();
    }

    /**
     * Enqueues packet work and executes {@code action} only when the sender is a ServerPlayer.
     */
    public static void withServerPlayer(IPayloadContext context, String packetName, Consumer<ServerPlayer> action) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Ignoring {} packet with non-server-player context",
                        packetName
                );
                return;
            }
            action.accept(player);
        });
    }
}
