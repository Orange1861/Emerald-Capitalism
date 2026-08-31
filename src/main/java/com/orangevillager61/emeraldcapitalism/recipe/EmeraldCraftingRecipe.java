package com.orangevillager61.emeraldcapitalism.recipe;

import com.orangevillager61.emeraldcapitalism.registry.ECAPRecipeSerializers;
import com.orangevillager61.emeraldcapitalism.registry.ECAPRecipeTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/** A shaped recipe available in the Bank's Emerald Crafting grid. */
public final class EmeraldCraftingRecipe extends ShapedRecipe {
    public EmeraldCraftingRecipe(String group, CraftingBookCategory category,
                                 ShapedRecipePattern pattern, ItemStack result,
                                 boolean showNotification) {
        super(group, category, pattern, result, showNotification);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ECAPRecipeSerializers.EMERALD_CRAFTING.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ECAPRecipeTypes.EMERALD_CRAFTING.get();
    }

    public static final class Serializer implements RecipeSerializer<EmeraldCraftingRecipe> {
        private static EmeraldCraftingRecipe fromShaped(ShapedRecipe recipe) {
            return new EmeraldCraftingRecipe(recipe.getGroup(), recipe.category(), recipe.pattern,
                    recipe.getResultItem(null), recipe.showNotification());
        }

        private static ShapedRecipe toShaped(EmeraldCraftingRecipe recipe) {
            return new ShapedRecipe(recipe.getGroup(), recipe.category(), recipe.pattern,
                    recipe.getResultItem(null), recipe.showNotification());
        }

        @Override
        public com.mojang.serialization.MapCodec<EmeraldCraftingRecipe> codec() {
            return RecipeSerializer.SHAPED_RECIPE.codec().xmap(
                    EmeraldCraftingRecipe.Serializer::fromShaped,
                    EmeraldCraftingRecipe.Serializer::toShaped);
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, EmeraldCraftingRecipe> streamCodec() {
            return RecipeSerializer.SHAPED_RECIPE.streamCodec().map(
                    EmeraldCraftingRecipe.Serializer::fromShaped,
                    EmeraldCraftingRecipe.Serializer::toShaped);
        }
    }
}
