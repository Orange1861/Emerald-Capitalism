package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Delivers a normal chat message across the 1.21.4 Player API change. */
public final class PlayerMessageUtils {
    private PlayerMessageUtils() {
    }

    public static void send(Player player, Component message) {
//? if >=1.21.4 {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message);
        } else {
            player.displayClientMessage(message, false);
        }
//?} else {
/*        player.sendSystemMessage(message);
 *///?}
    }
}
