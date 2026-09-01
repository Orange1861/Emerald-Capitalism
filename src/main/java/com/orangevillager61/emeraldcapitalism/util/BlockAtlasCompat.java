package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.resources.ResourceLocation;

/** Provides the block texture atlas location across the renderer API split. */
public final class BlockAtlasCompat {
    private BlockAtlasCompat() {
    }

    public static ResourceLocation location() {
//? if >=1.21.4 {
        return ResourceLocation.withDefaultNamespace("textures/atlas/blocks.png");
//?} else {
/*        return net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS;
 *///?}
    }
}
