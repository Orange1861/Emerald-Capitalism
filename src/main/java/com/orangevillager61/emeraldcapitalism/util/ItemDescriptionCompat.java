package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.world.item.ItemStack;

/** Bridges the item-stack translation-key accessor removed in 1.21.4. */
public final class ItemDescriptionCompat {
    private ItemDescriptionCompat() {
    }

    public static String get(ItemStack stack) {
//? if >=1.21.4 {
        return stack.getItem().getDescriptionId();
//?} else {
/*        return stack.getDescriptionId();
 *///?}
    }
}
