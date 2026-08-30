package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class EmeraldConsolidationUtils {

    private static final int EMERALD_CONSOLIDATION_THRESHOLD = 56;

    private EmeraldConsolidationUtils() {
    }

    public static void consolidateEmeralds(SimpleContainer inventory) {
        int emeraldCount = countItem(inventory, Items.EMERALD);
        if (emeraldCount <= EMERALD_CONSOLIDATION_THRESHOLD) {
            return;
        }

        int blocksToCraft = emeraldCount / 9;
        if (blocksToCraft <= 0) {
            return;
        }

        ItemStack blockStack = new ItemStack(Items.EMERALD_BLOCK, blocksToCraft);
        ItemStack remainder = inventory.addItem(blockStack);
        int addedBlocks = blocksToCraft - remainder.getCount();
        if (addedBlocks <= 0) {
            return;
        }

        removeItems(inventory, Items.EMERALD, addedBlocks * 9);
    }

    public static int countItem(SimpleContainer inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slotStack = inventory.getItem(i);
            if (slotStack.is(item)) {
                total += slotStack.getCount();
            }
        }
        return total;
    }

    /**
     * Counts the inventory's emerald value, treating each emerald block as nine
     * emeralds. This is the value used by bank deposits and villager eligibility
     * checks; it is intentionally separate from {@link #countItem}, which counts
     * physical stacks of one item type.
     */
    public static int countEmeraldValue(SimpleContainer inventory) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            total += countEmeraldValue(inventory.getItem(i));
        }
        return total;
    }

    /** Counts the emerald value represented by one stack. */
    public static int countEmeraldValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(Items.EMERALD)) {
            return stack.getCount();
        }
        if (stack.is(Items.EMERALD_BLOCK)) {
            return stack.getCount() * 9;
        }
        return 0;
    }

    public static void removeItems(SimpleContainer inventory, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (remaining <= 0) {
                return;
            }
            ItemStack slotStack = inventory.getItem(i);
            if (!slotStack.is(item)) {
                continue;
            }
            int toRemove = Math.min(remaining, slotStack.getCount());
            slotStack.shrink(toRemove);
            remaining -= toRemove;
            if (slotStack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }
}
