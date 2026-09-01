package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Version bridge for server-side item drops. */
public final class EntityDropUtils {
    private EntityDropUtils() {
    }

    public static ItemEntity spawn(Entity entity, ServerLevel level, ItemStack stack) {
//? if >=1.21.4 {
        return entity.spawnAtLocation(level, stack);
//?} else {
/*        return entity.spawnAtLocation(stack);
 *///?}
    }

    public static ItemEntity spawn(Entity entity, ServerLevel level, ItemStack stack, float yOffset) {
//? if >=1.21.4 {
        return entity.spawnAtLocation(level, stack, yOffset);
//?} else {
/*        return entity.spawnAtLocation(stack, yOffset);
 *///?}
    }
}
