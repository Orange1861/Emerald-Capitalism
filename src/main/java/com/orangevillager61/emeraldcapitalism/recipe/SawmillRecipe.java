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
        super(ECAPRecipeTypes.SAWMILL.get(), ECAPRecipeSerializers.SAWMILL.get(), group, ingredient, result);
        this.inputCount = inputCount;
    }

    public int getInputCount() {
        return this.inputCount;
    }

    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        return input.item().getCount() >= this.inputCount && this.ingredient.test(input.item());
    }

    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(ECAPBlocks.SAWMILL.get());
    }

    public static final class Serializer implements net.minecraft.world.item.crafting.RecipeSerializer<SawmillRecipe> {
        private final MapCodec<SawmillRecipe> codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("group", "").forGetter(SawmillRecipe::getGroup),
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(recipe -> recipe.ingredient),
                Codec.intRange(1, 64).fieldOf("count").forGetter(SawmillRecipe::getInputCount),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(recipe -> recipe.result)
        ).apply(instance, SawmillRecipe::new));

        private final StreamCodec<RegistryFriendlyByteBuf, SawmillRecipe> streamCodec = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                SawmillRecipe::getGroup,
                Ingredient.CONTENTS_STREAM_CODEC,
                recipe -> recipe.ingredient,
                ByteBufCodecs.VAR_INT,
                SawmillRecipe::getInputCount,
                ItemStack.STREAM_CODEC,
                recipe -> recipe.result,
                SawmillRecipe::new
        );

        @Override
        public MapCodec<SawmillRecipe> codec() {
            return this.codec;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, SawmillRecipe> streamCodec() {
            return this.streamCodec;
        }
    }
}
