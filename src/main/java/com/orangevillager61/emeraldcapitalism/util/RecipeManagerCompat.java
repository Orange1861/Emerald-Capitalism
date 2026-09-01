package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/** Accesses the server recipe manager across the 1.21.4 recipe-access split. */
public final class RecipeManagerCompat {
    private RecipeManagerCompat() {
    }

    public static RecipeManager get(Level level) {
//? if >=1.21.4 {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("RecipeManager access requires a server level");
        }
        return serverLevel.recipeAccess();
//?} else {
/*        return level.getRecipeManager();
 *///?}
    }

    @SuppressWarnings("unchecked")
    public static <I extends RecipeInput, T extends Recipe<I>> java.util.List<RecipeHolder<T>> getRecipesFor(
            Level level, RecipeType<T> type, I input) {
//? if >=1.21.4 {
        if (!(level instanceof ServerLevel serverLevel)) {
            return java.util.List.of();
        }
        return serverLevel.recipeAccess().getRecipes().stream()
                .filter(holder -> holder.value().getType() == type)
                .map(holder -> (RecipeHolder<T>) holder)
                .filter(holder -> holder.value().matches(input, level))
                .toList();
//?} else {
/*        return get(level).getRecipesFor(type, input, level);
 *///?}
    }

    @SuppressWarnings("unchecked")
    public static <T extends Recipe<?>> java.util.List<RecipeHolder<T>> getAllRecipesFor(
            ServerLevel level, RecipeType<T> type) {
//? if >=1.21.4 {
        return level.recipeAccess().getRecipes().stream()
                .filter(holder -> holder.value().getType() == type)
                .map(holder -> (RecipeHolder<T>) holder)
                .toList();
//?} else {
/*        return (java.util.List<RecipeHolder<T>>) (java.util.List<?>)
                get(level).getAllRecipesFor((RecipeType) type);
 *///?}
    }
}
