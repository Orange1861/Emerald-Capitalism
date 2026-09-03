package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** Bridges the item-stack translation-key accessor removed in 1.21.4. */
public final class ItemDescriptionCompat {
    private ItemDescriptionCompat() {
    }

    public static String get(ItemStack stack) {
//? if >=1.21.4 {
        String baseDescriptionId = stack.getItem().getDescriptionId();
        if (!stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION)) {
            return baseDescriptionId;
        }
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .potion()
                .flatMap(potion -> potion.unwrapKey()
                        .map(key -> baseDescriptionId + ".effect." + key.location().getPath()))
                .orElse(baseDescriptionId);
//?} else {
/*        return stack.getDescriptionId();
 *///?}
    }
}
