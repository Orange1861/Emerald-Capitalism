package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.recipe.EmeraldCraftingRecipe;
import com.orangevillager61.emeraldcapitalism.recipe.SawmillRecipe;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Supplier;

public final class ECAPRecipeTypes {
    private static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        RECIPE_TYPES.register(modEventBus);
    }

    public static final Supplier<RecipeType<SawmillRecipe>> SAWMILL = RECIPE_TYPES.register(
            "sawmill", () -> RecipeType.simple(ModIds.id("sawmill")));

    public static final Supplier<RecipeType<EmeraldCraftingRecipe>> EMERALD_CRAFTING = RECIPE_TYPES.register(
            "emerald_crafting", () -> RecipeType.simple(ModIds.id("emerald_crafting")));

    private ECAPRecipeTypes() {}
}
