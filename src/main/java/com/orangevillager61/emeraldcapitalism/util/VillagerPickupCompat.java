package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Version bridge for the server-level argument added to villager pickup checks. */
public final class VillagerPickupCompat {
    private VillagerPickupCompat() {
    }

    public static boolean wants(Villager villager, Level level, ItemStack stack) {
//? if >=1.21.4 {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        return villager.wantsToPickUp(serverLevel, stack);
//?} else {
/*        return villager.wantsToPickUp(stack);
 *///?}
    }
}
