package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.recipe.SawmillRecipe;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;

/** Bridges recipe result access removed from single-item recipes in 1.21.4. */
public final class RecipeResultCompat {
    private RecipeResultCompat() {
    }

    public static ItemStack getSawmillResult(SawmillRecipe recipe, HolderLookup.Provider registries) {
//? if >=1.21.4 {
        return recipe.assemble(new SingleRecipeInput(ItemStack.EMPTY), registries);
//?} else {
/*        return recipe.getResultItem(registries);
 *///?}
    }
}
