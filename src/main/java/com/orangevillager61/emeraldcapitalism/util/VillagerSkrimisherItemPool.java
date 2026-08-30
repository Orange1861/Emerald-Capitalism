package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/** The single item pool shared by villagers and Emerald Skrimishers. */
public final class VillagerSkrimisherItemPool {

    private static final Set<Item> DIRECT_ITEMS = Set.of(
            Items.EMERALD,
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.EMERALD_BLOCK,
            Items.EMERALD_ORE,
            Items.DEEPSLATE_EMERALD_ORE,
            Items.IRON_INGOT,
            Items.IRON_BLOCK,
            Items.IRON_NUGGET,
            Items.COAL,
            Items.CHARCOAL,
            Items.STRING,
            Items.BONE,
            Items.ENDER_PEARL,
            Items.CHEST,
            Items.TRAPPED_CHEST,
            Items.ENDER_CHEST,
            Items.WHEAT,
            Items.WHEAT_SEEDS,
            Items.BEETROOT_SEEDS,
            Items.TORCHFLOWER_SEEDS,
            Items.PITCHER_POD,
            Items.APPLE,
            Items.GOLDEN_APPLE,
            ECAPItems.COMPACTED_ROTTEN_FLESH.get(),
            ECAPItems.ROTTEN_FLESH_COVER.get()
    );

    private VillagerSkrimisherItemPool() {
    }

    /**
     * Returns whether the item belongs to the combined holdable pool.
     * Food and tag-based entries are included here so modded foods, wool,
     * doors, axes, and saplings use the same rule without maintaining duplicate
     * item lists. Every item registered by Emerald Capitalism is included so a
     * new mod item does not require another pickup-code edit.
     */
    public static boolean contains(ItemStack stack) {
        return !stack.isEmpty()
                && (DIRECT_ITEMS.contains(stack.getItem())
                || isEmeraldCapitalismItem(stack)
                || stack.get(DataComponents.FOOD) != null
                || stack.is(ItemTags.WOOL)
                || stack.is(ItemTags.DOORS)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SAPLINGS));
    }

    private static boolean isEmeraldCapitalismItem(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && EmeraldCapitalism.MODID.equals(key.getNamespace());
    }
}
