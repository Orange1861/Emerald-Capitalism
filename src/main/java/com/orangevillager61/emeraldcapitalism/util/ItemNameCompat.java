package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/** Version bridge for the item display-name accessor. */
public final class ItemNameCompat {
    private ItemNameCompat() {
    }

    public static Component get(Item item) {
//? if >=1.21.4 {
        return item.getName();
//?} else {
/*        return item.getDescription();
 *///?}
    }
}
