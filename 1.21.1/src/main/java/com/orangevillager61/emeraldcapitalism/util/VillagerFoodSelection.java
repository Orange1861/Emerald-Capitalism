package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared food-priority rules for villager eating and food sharing. */
public final class VillagerFoodSelection {

    private VillagerFoodSelection() {
    }

    /**
     * Selects the highest-nutrition food, preferring every other food over
     * bread and regular apples. Ties retain the first inventory slot.
     */
    public static int findBestFoodSlot(SimpleContainer inventory) {
        int bestSlot = -1;
        int bestPriority = -1;
        int bestNutrition = -1;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) {
                continue;
            }

            int priority = isLastChoice(stack) ? 0 : 1;
            if (priority > bestPriority
                    || (priority == bestPriority && food.nutrition() > bestNutrition)) {
                bestSlot = slot;
                bestPriority = priority;
                bestNutrition = food.nutrition();
            }
        }
        return bestSlot;
    }

    public static boolean isLastChoice(ItemStack stack) {
        return stack.is(Items.BREAD) || stack.is(Items.APPLE);
    }
}
