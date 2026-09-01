package com.orangevillager61.emeraldcapitalism.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPRecipeSerializers;
import com.orangevillager61.emeraldcapitalism.registry.ECAPRecipeTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;

/** A single-input sawmill recipe with a bounded number of input items. */
public class SawmillRecipe extends SingleItemRecipe {
    private final int inputCount;

    public SawmillRecipe(String group, Ingredient ingredient, int inputCount, ItemStack result) {
//? if >=1.21.4 {
        super(group, ingredient, result);
//?} else {
/*        super(ECAPRecipeTypes.SAWMILL.get(), ECAPRecipeSerializers.SAWMILL.get(), group, ingredient, result);
 *///?}
        this.inputCount = inputCount;
    }

    public int getInputCount() {
        return this.inputCount;
    }

//? if >=1.21.4 {
    @Override
    public net.minecraft.world.item.crafting.RecipeSerializer<? extends SawmillRecipe> getSerializer() {
        return ECAPRecipeSerializers.SAWMILL.get();
    }

    @Override
    public net.minecraft.world.item.crafting.RecipeType<? extends SawmillRecipe> getType() {
        return ECAPRecipeTypes.SAWMILL.get();
    }

    @Override
    public net.minecraft.world.item.crafting.RecipeBookCategory recipeBookCategory() {
        return net.minecraft.world.item.crafting.RecipeBookCategories.STONECUTTER;
    }
//?} else {
//?}

    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        return input.item().getCount() >= this.inputCount &&
//? if >=1.21.4 {
                this.input().test(input.item());
//?} else {
/*                this.ingredient.test(input.item());
 *///?}
    }

//? if >=1.21.4 {
//?} else {
/*    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(ECAPBlocks.SAWMILL.get());
    }
 *///?}

    public static final class Serializer implements net.minecraft.world.item.crafting.RecipeSerializer<SawmillRecipe> {
        private final MapCodec<SawmillRecipe> codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
//? if >=1.21.4 {
                Codec.STRING.optionalFieldOf("group", "").forGetter(SawmillRecipe::group),
                Ingredient.CODEC.fieldOf("ingredient").forGetter(SawmillRecipe::input),
//?} else {
/*                Codec.STRING.optionalFieldOf("group", "").forGetter(SawmillRecipe::getGroup),
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(recipe -> recipe.ingredient),
 *///?}
                Codec.intRange(1, 64).fieldOf("count").forGetter(SawmillRecipe::getInputCount),
//? if >=1.21.4 {
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(SawmillRecipe::result)
//?} else {
/*                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(recipe -> recipe.result)
 *///?}
        ).apply(instance, SawmillRecipe::new));

        private final StreamCodec<RegistryFriendlyByteBuf, SawmillRecipe> streamCodec = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
//? if >=1.21.4 {
                SawmillRecipe::group,
//?} else {
/*                SawmillRecipe::getGroup,
 *///?}
                Ingredient.CONTENTS_STREAM_CODEC,
//? if >=1.21.4 {
                SawmillRecipe::input,
//?} else {
/*                recipe -> recipe.ingredient,
 *///?}
                ByteBufCodecs.VAR_INT,
                SawmillRecipe::getInputCount,
                ItemStack.STREAM_CODEC,
//? if >=1.21.4 {
                SawmillRecipe::result,
//?} else {
/*                recipe -> recipe.result,
 *///?}
                SawmillRecipe::new
        );

        @Override
        public MapCodec<SawmillRecipe> codec() {
            return this.codec;
        }

        @SuppressWarnings("deprecation")
        @Override
        public StreamCodec<RegistryFriendlyByteBuf, SawmillRecipe> streamCodec() {
            return this.streamCodec;
        }
    }
}
